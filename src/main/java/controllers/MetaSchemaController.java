package controllers;

import auth.TenantContext;
import auth.TenantContextHolder;
import dtos.SchemaExportDto;
import filters.RequiredTenantContextFilter;
import filters.TenantContextFilter;
import filters.admin.AdminAuthFilter;
import io.mangoo.annotations.FilterWith;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import io.mangoo.utils.JsonUtils;
import jakarta.inject.Inject;
import services.SchemaService;
import services.SchemaService.SchemaImportResult;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@FilterWith({AdminAuthFilter.class, TenantContextFilter.class, RequiredTenantContextFilter.class})
public class MetaSchemaController {
    private final SchemaService schemaService;

    @Inject
    public MetaSchemaController(SchemaService schemaService) {
        this.schemaService = Objects.requireNonNull(schemaService, "schemaService must not be null");
    }

    public Response export(Request request) {
        TenantContext ctx = TenantContextHolder.require(request);
        SchemaExportDto schema = schemaService.export(ctx);

        try {
            byte[] json = JsonUtils.getMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(schema);
            String filename = "schema-" + ctx.activeTenantId() + "-" + Instant.now().toString().substring(0, 10) + ".json";
            return Response.ok()
                    .contentType("application/json")
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .bodyBinary(json);
        } catch (Exception e) {
            return Response.internalServerError();
        }
    }

    public Response importSchema(Request request) {
        TenantContext ctx = TenantContextHolder.require(request);

        SchemaExportDto schema;
        try {
            schema = JsonUtils.getMapper().readValue(request.getBody(), SchemaExportDto.class);
        } catch (Exception e) {
            return Response.badRequest().bodyJson("{\"error\":\"Invalid schema JSON\"}");
        }

        if (schema.collections() == null) {
            return Response.badRequest().bodyJson("{\"error\":\"Missing collections field\"}");
        }

        try {
            SchemaImportResult result = schemaService.importSchema(ctx, schema);
            return Response.ok().bodyJson(JsonUtils.getMapper().writeValueAsString(result));
        } catch (IllegalArgumentException e) {
            // A file the import refuses to apply is the caller's problem, not a server fault, and
            // the message names what to fix. Built through a map so that a quote in a collection
            // name cannot break out of the JSON.
            return Response.badRequest().bodyJson(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return Response.internalServerError().bodyJson(Map.of("error", String.valueOf(e.getMessage())));
        }
    }
}
