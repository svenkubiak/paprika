package models;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

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
        String maxDateTime
) {
    public static final long DEFAULT_MAX_SIZE = 5L * 1024L * 1024L;

    public FieldOptions(String collection, Long maxSize, List<String> mimeTypes, Integer maxSelect, List<String> values) {
        this(collection, maxSize, mimeTypes, maxSelect, values, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
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

    public boolean cascadeDeleteOrDefault() {
        return Boolean.TRUE.equals(cascadeDelete);
    }

    public static FieldOptions forRelation(String collection, Integer maxSelect, Boolean cascadeDelete) {
        return new FieldOptions(collection, null, null, maxSelect, null, cascadeDelete, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static FieldOptions forRelation(String collection) {
        return forRelation(collection, 1, false);
    }

    public static FieldOptions forFile(long maxSize, List<String> mimeTypes, int maxSelect) {
        return new FieldOptions(null, maxSize, mimeTypes, maxSelect, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static FieldOptions forSelect(List<String> values, int maxSelect) {
        return new FieldOptions(null, null, null, maxSelect, values, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static FieldOptions forString(Integer minLength, Integer maxLength, String pattern) {
        return new FieldOptions(null, null, null, null, null, null, minLength, maxLength, pattern, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static FieldOptions forNumber(Double min, Double max) {
        return new FieldOptions(null, null, null, null, null, null, null, null, null, min, max, null, null, null, null, null, null, null, null, null, null);
    }

    public static FieldOptions forJson(Integer maxBytes, Integer maxDepth, Boolean onlyObject, Boolean onlyArray) {
        return new FieldOptions(null, null, null, null, null, null, null, null, null, null, null, maxBytes, maxDepth, onlyObject, onlyArray, null, null, null, null, null, null);
    }

    public static FieldOptions forDateRange(String minDate, String maxDate) {
        return new FieldOptions(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, minDate, maxDate, null, null, null, null);
    }

    public static FieldOptions forTimeRange(String minTime, String maxTime) {
        return new FieldOptions(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, minTime, maxTime, null, null);
    }

    public static FieldOptions forDateTimeRange(String minDateTime, String maxDateTime) {
        return new FieldOptions(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, minDateTime, maxDateTime);
    }
}
