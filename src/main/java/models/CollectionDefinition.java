package models;

import org.apache.logging.log4j.core.config.plugins.validation.constraints.Required;

import java.util.List;

public record CollectionDefinition (
        String id,

        @Required
        String name,

        List<FieldDefinition> fields,
        List<IndexDefinition> indexes,
        CollectionRules rules,
        Boolean system
) {
    public CollectionRules rulesOrDefault() {
        return rules != null ? rules : CollectionRules.locked();
    }

    public boolean isSystem() {
        return Boolean.TRUE.equals(system);
    }
}
