package utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import enums.FieldType;
import io.mangoo.routing.Attachment;
import io.mangoo.routing.bindings.Form;
import io.mangoo.routing.bindings.Request;
import io.mangoo.utils.JsonUtils;
import io.mangoo.utils.RequestUtils;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.form.FormData;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import models.CollectionDefinition;
import models.FieldDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.*;

public final class MultipartSupport {
    public static final String PARSED_FILES_ATTRIBUTE = "paprika.multipart.files";
    public static final String JSON_BODY_ATTRIBUTE = "paprika.multipart.jsonBody";
    public static final String STRUCTURED_DATA_FIELD = "__paprika_data";

    private MultipartSupport() {
    }

    public static boolean isMultipart(Request request) {
        String contentType = request.getHeader(Headers.CONTENT_TYPE);
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data");
    }

    public static void prepare(Request request, CollectionDefinition definition) throws IOException {
        if (!isMultipart(request)) {
            return;
        }

        ParsedMultipart parsed = parseMultipart(request);
        request.addAttribute(PARSED_FILES_ATTRIBUTE, parsed.uploads());
        request.addAttribute(JSON_BODY_ATTRIBUTE, buildJsonBody(definition, parsed).toString());
    }

    public static String effectiveJsonBody(Request request) {
        Object attribute = request.getAttribute(JSON_BODY_ATTRIBUTE);
        if (attribute instanceof String body && !body.isBlank()) {
            return body;
        }
        return request.getBody();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, List<UploadedFile>> uploads(Request request) {
        Object attribute = request.getAttribute(PARSED_FILES_ATTRIBUTE);
        if (attribute instanceof Map<?, ?> map) {
            return (Map<String, List<UploadedFile>>) map;
        }
        return Map.of();
    }

    public static List<UploadedFile> uploadsForField(Request request, String fieldName) {
        return uploads(request).getOrDefault(fieldName, List.of());
    }

    public static ObjectNode buildJsonBody(CollectionDefinition definition, ParsedMultipart parsed) {
        ObjectNode node = structuredData(parsed.textFields().get(STRUCTURED_DATA_FIELD));
        for (Map.Entry<String, String> entry : parsed.textFields().entrySet()) {
            if (STRUCTURED_DATA_FIELD.equals(entry.getKey()) || isFileFieldName(entry.getKey(), definition)) {
                continue;
            }
            FieldDefinition field = fieldDefinition(definition, entry.getKey());
            if (field != null && usesJsonValue(field)) {
                try {
                    node.set(entry.getKey(), JsonUtils.getMapper().readTree(entry.getValue()));
                } catch (JsonProcessingException e) {
                    node.put(entry.getKey(), entry.getValue());
                }
                continue;
            }
            node.put(entry.getKey(), entry.getValue());
        }
        return node;
    }

    private static ObjectNode structuredData(String value) {
        if (value == null || value.isBlank()) {
            return JsonUtils.getMapper().createObjectNode();
        }
        try {
            if (JsonUtils.getMapper().readTree(value) instanceof ObjectNode object) {
                return object.deepCopy();
            }
        } catch (JsonProcessingException ignored) {
            // Validation handles missing required fields from malformed structured data.
        }
        return JsonUtils.getMapper().createObjectNode();
    }

    private static boolean usesJsonValue(FieldDefinition field) {
        return field.type() == FieldType.JSON
                || field.type() == FieldType.NUMBER
                || field.type() == FieldType.BOOLEAN
                || ((field.type() == FieldType.SELECT || field.type() == FieldType.RELATION)
                && field.optionsOrDefault().maxSelectOrDefault() > 1);
    }

    public static Optional<Form> form(Request request) {
        HttpServerExchange exchange = exchange(request);
        if (exchange == null) {
            return Optional.empty();
        }
        Attachment attachment = exchange.getAttachment(RequestUtils.getAttachmentKey());
        return attachment != null && attachment.getForm() != null
                ? Optional.of(attachment.getForm())
                : Optional.empty();
    }

    public static ParsedMultipart parseMultipart(Request request) throws IOException {
        HttpServerExchange exchange = exchange(request);
        Optional<Form> mangooForm = form(request);
        FormData formData = exchange != null ? exchange.getAttachment(FormDataParser.FORM_DATA) : null;

        if (mangooForm.isEmpty() && formData == null) {
            return new ParsedMultipart(Map.of(), Map.of());
        }

        Map<String, String> textFields = mangooForm
                .map(form -> new LinkedHashMap<>(form.getValues()))
                .orElseGet(() -> new LinkedHashMap<>(textFieldsFromFormData(formData)));

        Map<String, List<UploadedFile>> uploads = uploadsFromFormData(formData);
        if (uploads.isEmpty() && mangooForm.isPresent()) {
            uploads = uploadsFromMangooForm(mangooForm.get(), formData);
        }

        return new ParsedMultipart(Map.copyOf(textFields), Map.copyOf(uploads));
    }

    public static Map<String, List<UploadedFile>> parseUploads(Request request) throws IOException {
        return parseMultipart(request).uploads();
    }

    private static Map<String, String> textFieldsFromFormData(FormData formData) {
        if (formData == null) {
            return Map.of();
        }
        Map<String, String> textFields = new LinkedHashMap<>();
        for (String name : formData) {
            for (FormData.FormValue value : formData.get(name)) {
                if (!value.isFileItem()) {
                    String text = value.getValue();
                    if (text != null) {
                        textFields.put(name, text);
                    }
                }
            }
        }
        return textFields;
    }

    private static Map<String, List<UploadedFile>> uploadsFromFormData(FormData formData) {
        if (formData == null) {
            return Map.of();
        }

        Map<String, List<UploadedFile>> uploads = new LinkedHashMap<>();
        for (String name : formData) {
            for (FormData.FormValue value : formData.get(name)) {
                if (!value.isFileItem()) {
                    continue;
                }
                try {
                    FormData.FileItem item = value.getFileItem();
                    byte[] bytes = readAll(item.getInputStream());
                    String fileName = extractFileName(value);
                    uploads.computeIfAbsent(name, ignored -> new ArrayList<>())
                            .add(new UploadedFile(fileName, bytes, MimeTypes.detect(bytes, fileName)));
                } catch (IOException ignored) {
                    // Stream may already be consumed by mangoo FormHandler; fall back below.
                }
            }
        }
        return uploads;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<UploadedFile>> uploadsFromMangooForm(Form mangooForm, FormData formData) {
        Map<String, List<UploadedFile>> uploads = new LinkedHashMap<>();
        try {
            Field filesField = mangooForm.getClass().getSuperclass().getDeclaredField("files");
            filesField.setAccessible(true);
            Map<String, byte[]> files = (Map<String, byte[]>) filesField.get(mangooForm);
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                byte[] bytes = entry.getValue();
                String fileName = fileNameFromFormData(formData, entry.getKey()).orElse("upload.bin");
                uploads.put(entry.getKey(), List.of(new UploadedFile(
                        fileName,
                        bytes,
                        MimeTypes.detect(bytes, fileName))));
            }
        } catch (ReflectiveOperationException ignored) {
            // Fall back to empty uploads when mangoo internals change.
        }
        return uploads;
    }

    private static Optional<String> fileNameFromFormData(FormData formData, String fieldName) {
        if (formData == null || !formData.contains(fieldName)) {
            return Optional.empty();
        }
        for (FormData.FormValue value : formData.get(fieldName)) {
            if (value.isFileItem()) {
                return Optional.of(extractFileName(value));
            }
        }
        return Optional.empty();
    }

    private static FieldDefinition fieldDefinition(CollectionDefinition definition, String name) {
        if (definition.fields() == null) {
            return null;
        }
        return definition.fields().stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    private static boolean isFileFieldName(String key, CollectionDefinition definition) {
        if (definition.fields() == null) {
            return false;
        }
        return definition.fields().stream()
                .anyMatch(field -> field.type() == FieldType.FILE && field.name().equals(key));
    }

    private static String extractFileName(FormData.FormValue value) {
        HeaderValues headers = value.getHeaders().get(Headers.CONTENT_DISPOSITION);
        if (headers == null) {
            return "upload.bin";
        }
        for (String header : headers) {
            int index = header.toLowerCase(Locale.ROOT).indexOf("filename=");
            if (index >= 0) {
                String raw = header.substring(index + 9).trim();
                if (raw.startsWith("\"") && raw.endsWith("\"")) {
                    raw = raw.substring(1, raw.length() - 1);
                }
                if (!raw.isBlank()) {
                    return raw;
                }
            }
        }
        return "upload.bin";
    }

    private static byte[] readAll(InputStream inputStream) throws IOException {
        return inputStream.readAllBytes();
    }

    private static HttpServerExchange exchange(Request request) {
        try {
            Field field = Request.class.getDeclaredField("httpServerExchange");
            field.setAccessible(true);
            return (HttpServerExchange) field.get(request);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public record UploadedFile(String fileName, byte[] bytes, String mimeType) {
    }

    public record ParsedMultipart(Map<String, String> textFields, Map<String, List<UploadedFile>> uploads) {
    }
}
