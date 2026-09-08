package models;

import enums.IndexDirection;

public record IndexField(
        String field,
        IndexDirection direction
) {
}
