package models;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FieldOptions(
        String collection,
        Long maxSize,
        List<String> mimeTypes,
        Integer maxSelect,
        List<String> values,
        Boolean cascadeDelete,
        Integer minLength,
        Integer maxLength,
        String pattern,
        Double numberMin,
        Double numberMax,
        Integer maxBytes,
        Integer maxDepth,
        Boolean onlyObject,
        Boolean onlyArray,
        String minDate,
        String maxDate,
        String minTime,
        String maxTime,
        String minDateTime,
        String maxDateTime,
        List<Integer> imageWidths,
        Boolean multiline
) {
    // Below Undertow's 4 MiB body limit (undertow.maxentitysize), leaving headroom for the
    // multipart boundaries, part headers and other fields of the same request.
    public static final long DEFAULT_MAX_SIZE = 4_000_000L;

    // Every width multiplies the storage of every upload to the field.
    public static final int MAX_IMAGE_WIDTHS = 4;

    public static final int MAX_IMAGE_WIDTH = 4096;

    public FieldOptions(String collection, Long maxSize, List<String> mimeTypes, Integer maxSelect, List<String> values) {
        this(collection, maxSize, mimeTypes, maxSelect, values, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public int maxSelectOrDefault() {
        return maxSelect != null && maxSelect > 0 ? maxSelect : 1;
    }

    public long maxSizeOrDefault() {
        return maxSize != null && maxSize > 0 ? maxSize : DEFAULT_MAX_SIZE;
    }

    public List<String> mimeTypesOrEmpty() {
        return mimeTypes != null ? mimeTypes : List.of();
    }

    public List<String> valuesOrEmpty() {
        return values != null ? values : List.of();
    }

    // Sorted ascending so the download path's "next larger variant" lookup is a plain scan.
    public List<Integer> imageWidthsOrEmpty() {
        if (imageWidths == null) {
            return List.of();
        }
        return imageWidths.stream()
                .filter(Objects::nonNull)
                .filter(width -> width > 0)
                .distinct()
                .sorted()
                .toList();
    }

    public boolean cascadeDeleteOrDefault() {
        return Boolean.TRUE.equals(cascadeDelete);
    }

    // Admin UI hint only; no validator behaves differently for it.
    public boolean multilineOrDefault() {
        return Boolean.TRUE.equals(multiline);
    }

    public static FieldOptions forRelation(String collection, Integer maxSelect, Boolean cascadeDelete) {
        return new FieldOptions(collection, null, null, maxSelect, null, cascadeDelete, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static FieldOptions forRelation(String collection) {
        return forRelation(collection, 1, false);
    }

    public static FieldOptions forFile(long maxSize, List<String> mimeTypes, int maxSelect) {
        return forFile(maxSize, mimeTypes, maxSelect, null);
    }

    public static FieldOptions forFile(long maxSize, List<String> mimeTypes, int maxSelect, List<Integer> imageWidths) {
        return new FieldOptions(null, maxSize, mimeTypes, maxSelect, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, imageWidths, null);
    }

    public static FieldOptions forSelect(List<String> values, int maxSelect) {
        return new FieldOptions(null, null, null, maxSelect, values, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static FieldOptions forString(Integer minLength, Integer maxLength, String pattern) {
        return forString(minLength, maxLength, pattern, null);
    }

    public static FieldOptions forString(Integer minLength, Integer maxLength, String pattern, Boolean multiline) {
        return new FieldOptions(null, null, null, null, null, null, minLength, maxLength, pattern, null, null, null, null, null, null, null, null, null, null, null, null, null, multiline);
    }

    public static FieldOptions forNumber(Double min, Double max) {
        return new FieldOptions(null, null, null, null, null, null, null, null, null, min, max, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static FieldOptions forJson(Integer maxBytes, Integer maxDepth, Boolean onlyObject, Boolean onlyArray) {
        return new FieldOptions(null, null, null, null, null, null, null, null, null, null, null, maxBytes, maxDepth, onlyObject, onlyArray, null, null, null, null, null, null, null, null);
    }

    public static FieldOptions forDateRange(String minDate, String maxDate) {
        return new FieldOptions(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, minDate, maxDate, null, null, null, null, null, null);
    }

    public static FieldOptions forTimeRange(String minTime, String maxTime) {
        return new FieldOptions(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, minTime, maxTime, null, null, null, null);
    }

    public static FieldOptions forDateTimeRange(String minDateTime, String maxDateTime) {
        return new FieldOptions(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, minDateTime, maxDateTime, null, null);
    }
}
