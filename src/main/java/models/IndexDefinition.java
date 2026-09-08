package models;

import java.util.List;

public record IndexDefinition(
        String name,
        List<IndexField> fields,
        boolean unique
) {
}
