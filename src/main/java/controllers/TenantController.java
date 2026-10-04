package controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import dtos.*;
import filters.admin.AdminAuthFilter;
import helpers.HashingCapacityResponse;
import io.mangoo.annotations.FilterWith;
import io.mangoo.exceptions.MangooHashingException;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import io.mangoo.utils.JsonUtils;
import io.undertow.util.StatusCodes;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import models.TenantDefinition;
import org.apache.commons.lang3.StringUtils;
import services.ApiKeyService;
import services.TenantService;
import services.TenantUserService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@FilterWith(AdminAuthFilter.class)
public class TenantController {
    // Everything else in the body is a custom field of the tenant's users schema.
    private static final Set<String> CORE_USER_FIELDS = Set.of("username", "email", "password");

    // Fixed at creation; naming one in an update is refused rather than ignored.
    private static final Set<String> IMMUTABLE_API_KEY_FIELDS =
            Set.of("bypassRules", "bypassHooks", "name", "userId", "expiresAt");

    private final TenantService tenantService;
    private final TenantUserService tenantUserService;
    private final ApiKeyService apiKeyService;

    @Inject
    public TenantController(
            TenantService tenantService,
            TenantUserService tenantUserService,
            ApiKeyService apiKeyService) {
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
        this.tenantUserService = Objects.requireNonNull(tenantUserService, "tenantUserService must not be null");
        this.apiKeyService = Objects.requireNonNull(apiKeyService, "apiKeyService must not be null");
    }

    public Response list() {
        return Response.ok().bodyJson(tenantService.listAll());
    }

    public Response create(@NotNull(message = "Request body is required") @Valid TenantDto tenantDto) {
        try {
            TenantDefinition tenant = tenantService.create(tenantDto.name(), tenantDto.slug());
            return Response.created().bodyJson(tenant);
        } catch (IllegalArgumentException e) {
            return Response.badRequest().bodyJson(Map.of("error", e.getMessage()));
        }
    }

    public Response read(String tenantId) {
        return tenantService.findById(tenantId)
                .map(Response.ok()::bodyJson)
                .orElseGet(Response::notFound);
    }

    public Response update(String tenantId, @NotNull(message = "Request body is required") @Valid TenantUpdateDto tenantDto) {
        try {
            return tenantService.update(
                            tenantId,
                            tenantDto.name(),
                            tenantDto.slug(),
                            null,
                            tenantDto.registrationEnabled(),
                            tenantDto.passwordResetEnabled(),
                            tenantDto.emailVerificationEnabled(),
                            tenantDto.emailVerificationRequired(),
                            tenantDto.passwordResetUrl(),
                            tenantDto.emailVerificationUrl(),
                            tenantDto.webhookAllowlist(),
                            tenantDto.tokenIssuers())
                    .map(Response.ok()::bodyJson)
                    .orElseGet(Response::notFound);
        } catch (IllegalArgumentException e) {
            return Response.badRequest().bodyJson(Map.of("error", e.getMessage()));
        }
    }

    public Response delete(String tenantId) {
        if (tenantService.deleteWithCascade(tenantId)) {
            // The keys live in the system database, so dropping the tenant database does not remove them.
            apiKeyService.deleteForTenant(tenantId);
            return Response.status(StatusCodes.NO_CONTENT);
        }
        return Response.notFound();
    }

    public Response listUsers(String tenantId) {
        return tenantService.findById(tenantId)
                .map(tenant -> Response.ok().bodyJson(tenantUserService.listUsers(tenant)))
                .orElseGet(Response::notFound);
    }

    public Response createUser(
            String tenantId,
            @NotNull(message = "Request body is required") @Valid UserDto userDto,
            Request request) {
        try {
            Map<String, Object> customFields = customUserFields(request);
            return tenantService.findById(tenantId)
                    .map(tenant -> Response.created().bodyJson(tenantUserService.createUser(
                            tenant,
                            userDto.username(),
                            userDto.email(),
                            userDto.password(),
                            customFields)))
                    .orElseGet(Response::notFound);
        } catch (IllegalArgumentException e) {
            return Response.badRequest().bodyJson(Map.of("error", e.getMessage()));
        } catch (MangooHashingException e) {
            return HashingCapacityResponse.refused();
        }
    }

    public Response updateUser(
            String tenantId,
            String userId,
            @NotNull(message = "Request body is required") @Valid UserUpdateDto userDto,
            Request request) {

        try {
            Map<String, Object> customFields = customUserFields(request);
            return tenantService.findById(tenantId)
                    .flatMap(tenant -> tenantUserService.updateUser(
                            tenant,
                            userId,
                            userDto.username(),
                            userDto.email(),
                            userDto.password(),
                            customFields))
                    .map(Response.ok()::bodyJson)
                    .orElseGet(Response::notFound);
        } catch (IllegalArgumentException e) {
            return Response.badRequest().bodyJson(Map.of("error", e.getMessage()));
        } catch (MangooHashingException e) {
            return HashingCapacityResponse.refused();
        }
    }

    // null values are kept: on an update that is how the editor clears a field.
    private Map<String, Object> customUserFields(Request request) {
        String body = request.getBody();
        if (StringUtils.isBlank(body)) {
            return Map.of();
        }

        try {
            JsonNode node = JsonUtils.getMapper().readTree(body);
            if (!node.isObject()) {
                return Map.of();
            }

            Map<String, Object> customFields = new LinkedHashMap<>();
            node.properties().forEach(entry -> {
                if (!CORE_USER_FIELDS.contains(entry.getKey())) {
                    customFields.put(
                            entry.getKey(),
                            JsonUtils.getMapper().convertValue(entry.getValue(), Object.class));
                }
            });

            return customFields;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON body");
        }
    }

    public Response deleteUser(String tenantId, String userId) {
        return tenantService.findById(tenantId)
                .filter(tenant -> tenantUserService.deleteUser(tenant, userId))
                .map(tenant -> {
                    // A deleted user must not leave working credentials behind
                    apiKeyService.revokeForUser(tenant.id(), userId);
                    return Response.status(StatusCodes.NO_CONTENT);
                })
                .orElseGet(Response::notFound);
    }

    public Response listApiKeys(String tenantId) {
        return tenantService.findById(tenantId)
                .map(tenant -> Response.ok().bodyJson(apiKeyService.list(tenant.id())))
                .orElseGet(Response::notFound);
    }

    // The only response that ever carries the plaintext key; it is stored hashed.
    public Response createApiKey(
            String tenantId,
            @NotNull(message = "Request body is required") @Valid ApiKeyDto apiKeyDto) {

        try {
            return tenantService.findById(tenantId)
                    .map(tenant -> {
                        ApiKeyService.CreatedApiKey created = apiKeyService.create(
                                tenant,
                                apiKeyDto.name(),
                                apiKeyDto.userId(),
                                apiKeyDto.expiresAt(),
                                Boolean.TRUE.equals(apiKeyDto.bypassRules()),
                                Boolean.TRUE.equals(apiKeyDto.bypassHooks()),
                                apiKeyDto.allowedCidrs());

                        Map<String, Object> body = new LinkedHashMap<>(created.key());
                        body.put("key", created.plaintext());
                        return Response.created().bodyJson(body);
                    })
                    .orElseGet(Response::notFound);
        } catch (IllegalArgumentException e) {
            return Response.badRequest().bodyJson(Map.of("error", e.getMessage()));
        }
    }

    // Unparsable ranges and creation-only fields are a 400, never silently dropped: the caller
    // would otherwise believe a network was excluded or a flag was set.
    public Response updateApiKey(
            String tenantId,
            String keyId,
            @NotNull(message = "Request body is required") @Valid ApiKeyUpdateDto apiKeyUpdateDto,
            Request request) {

        String immutable = immutableFieldIn(request);
        if (immutable != null) {
            return Response.badRequest().bodyJson(Map.of("error",
                    immutable + " is set when the key is created and cannot be changed afterwards"));
        }

        try {
            return tenantService.findById(tenantId)
                    .filter(tenant -> apiKeyService.updateAllowedCidrs(
                            tenant.id(), keyId, apiKeyUpdateDto.allowedCidrs()))
                    .map(tenant -> Response.status(StatusCodes.NO_CONTENT))
                    .orElseGet(Response::notFound);
        } catch (IllegalArgumentException e) {
            return Response.badRequest().bodyJson(Map.of("error", e.getMessage()));
        }
    }

    // Checked on the raw body because the DTO does not carry those fields at all.
    private String immutableFieldIn(Request request) {
        String body = request.getBody();
        if (StringUtils.isBlank(body)) {
            return null;
        }

        try {
            JsonNode node = JsonUtils.getMapper().readTree(body);
            for (String field : IMMUTABLE_API_KEY_FIELDS) {
                if (node.has(field)) {
                    return field;
                }
            }
        } catch (JsonProcessingException e) {
            // An unreadable body fails on the DTO binding instead.
            return null;
        }

        return null;
    }

    // Keeps the record, so the key stays auditable.
    public Response revokeApiKey(String tenantId, String keyId) {
        return tenantService.findById(tenantId)
                .filter(tenant -> apiKeyService.revoke(tenant.id(), keyId))
                .map(tenant -> Response.status(StatusCodes.NO_CONTENT))
                .orElseGet(Response::notFound);
    }

    public Response deleteApiKey(String tenantId, String keyId) {
        return tenantService.findById(tenantId)
                .filter(tenant -> apiKeyService.delete(tenant.id(), keyId))
                .map(tenant -> Response.status(StatusCodes.NO_CONTENT))
                .orElseGet(Response::notFound);
    }
}
