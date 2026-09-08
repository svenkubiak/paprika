package models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import enums.FieldType;
import io.mangoo.utils.JsonUtils;
import org.bson.codecs.pojo.annotations.BsonProperty;

public record FieldDefinition(
        String name,
        FieldType type,
        boolean required,
        boolean nullable,
        FieldOptions options,
        @JsonIgnore
        @BsonProperty("default")
        String defaultValueEncoded
) {

    public FieldDefinition(String name, FieldType type, boolean required, boolean nullable, FieldOptions options) {
        this(name, type, required, nullable, options, null);
    }

    public FieldDefinition(
            String name,
            FieldType type,
            boolean required,
            boolean nullable,
            FieldOptions options,
            Object defaultValue) {
        this(name, type, required, nullable, options, encodeDefault(defaultValue));
    }

    public FieldDefinition {
        if (defaultValueEncoded != null && !defaultValueEncoded.isBlank() && !isJsonLiteral(defaultValueEncoded)) {
            defaultValueEncoded = encodeDefault(defaultValueEncoded);
        }
    }

    @JsonCreator
    public static FieldDefinition create(
            @JsonProperty("name") String name,
            @JsonProperty("type") FieldType type,
            @JsonProperty("required") boolean required,
            @JsonProperty("nullable") boolean nullable,
            @JsonProperty("options") FieldOptions options,
            @JsonProperty("default") Object defaultValue) {
        return new FieldDefinition(name, type, required, nullable, options, defaultValue);
    }

    @JsonProperty("default")
    public Object defaultValue() {
        if (defaultValueEncoded == null || defaultValueEncoded.isBlank()) {
            return null;
        }
        try {
            return JsonUtils.getMapper().readValue(defaultValueEncoded, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid encoded default for field " + name, e);
        }
    }

    public FieldOptions optionsOrDefault() {
        return options != null ? options : FieldOptions.forRelation(null);
    }

    private static String encodeDefault(Object defaultValue) {
        if (defaultValue == null) {
            return null;
        }
        try {
            return JsonUtils.getMapper().writeValueAsString(defaultValue);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid default value", e);
        }
    }

    private static boolean isJsonLiteral(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        char first = trimmed.charAt(0);
        return first == '"'
                || first == '{'
                || first == '['
                || first == '-'
                || first == 't'
                || first == 'f'
                || first == 'n'
                || (first >= '0' && first <= '9');
    }
}
