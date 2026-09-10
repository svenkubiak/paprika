package dtos;

import models.CollectionDefinition;
import models.HookDefinition;

import java.util.List;

public record SchemaExportDto(
        String version,
        String exportedAt,
        List<CollectionDefinition> collections,
        List<HookDefinition> hooks
) {}
