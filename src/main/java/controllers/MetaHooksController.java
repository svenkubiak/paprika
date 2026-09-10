package controllers;

import auth.TenantContext;
import auth.TenantContextHolder;
import filters.RequiredTenantContextFilter;
import filters.TenantContextFilter;
import filters.admin.AdminAuthFilter;
import io.mangoo.annotations.FilterWith;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import models.HookDefinition;
import services.HookService;
import utils.DbUtils;

import java.util.Map;
import java.util.Objects;

@FilterWith({AdminAuthFilter.class, TenantContextFilter.class, RequiredTenantContextFilter.class})
public class MetaHooksController {
    private final HookService hookService;

    @Inject
    public MetaHooksController(HookService hookService) {
        this.hookService = Objects.requireNonNull(hookService, "hookService must not be null");
    }

    public Response list(String collection, Request request) {
        TenantContext ctx = TenantContextHolder.require(request);
        return Response.ok().bodyJson(hookService.listForCollection(ctx, collection));
    }

    public Response create(String collection, @Valid HookDefinition hookDefinition, Request request) {
        TenantContext ctx = TenantContextHolder.require(request);

        HookDefinition hook = new HookDefinition(
                DbUtils.id(),
                hookDefinition.name(),
                hookDefinition.description(),
                collection,
                hookDefinition.event(),
                hookDefinition.url(),
                hookDefinition.method(),
                hookDefinition.timeoutMs(),
                hookDefinition.secret(),
                hookDefinition.headers(),
                hookDefinition.enabled() == null || hookDefinition.enabled(),
                hookDefinition.priority(),
                hookDefinition.includeSchema(),
                hookDefinition.failOpen(),
                null,
                null
        );

        try {
            hookService.validate(hook, ctx);
        } catch (IllegalArgumentException e) {
            return Response.badRequest().bodyJson(Map.of("error", e.getMessage()));
        }

        hookService.insert(ctx, hook);
        return Response.created().bodyJson(hook);
    }

    public Response update(String collection, String id, @Valid HookDefinition hookDefinition, Request request) {
        TenantContext ctx = TenantContextHolder.require(request);

        HookDefinition current = hookService.findById(ctx, collection, id);
        if (current == null) {
            return Response.notFound();
        }

        HookDefinition updated = new HookDefinition(
                current.id(),
                hookDefinition.name() != null ? hookDefinition.name() : current.name(),
                hookDefinition.description() != null ? hookDefinition.description() : current.description(),
                collection,
                hookDefinition.event() != null ? hookDefinition.event() : current.event(),
                hookDefinition.url() != null ? hookDefinition.url() : current.url(),
                hookDefinition.method() != null ? hookDefinition.method() : current.method(),
                hookDefinition.timeoutMs() != null ? hookDefinition.timeoutMs() : current.timeoutMs(),
                hookDefinition.secret() != null ? hookDefinition.secret() : current.secret(),
                hookDefinition.headers() != null ? hookDefinition.headers() : current.headers(),
                hookDefinition.enabled() != null ? hookDefinition.enabled() : current.enabled(),
                hookDefinition.priority() != null ? hookDefinition.priority() : current.priority(),
                hookDefinition.includeSchema() != null ? hookDefinition.includeSchema() : current.includeSchema(),
                hookDefinition.failOpen() != null ? hookDefinition.failOpen() : current.failOpen(),
                current.applyToAllCollections(),
                current.targetCollections()
        );

        try {
            hookService.validate(updated, ctx);
        } catch (IllegalArgumentException e) {
            return Response.badRequest().bodyJson(Map.of("error", e.getMessage()));
        }

        hookService.replace(ctx, updated);
        return Response.ok().bodyJson(updated);
    }

    public Response delete(String collection, String id, Request request) {
        TenantContext ctx = TenantContextHolder.require(request);

        HookDefinition current = hookService.findById(ctx, collection, id);
        if (current == null) {
            return Response.notFound();
        }

        hookService.delete(ctx, id);
        return Response.ok();
    }

    public Response test(String collection, HookDefinition hookDefinition, Request request) {
        TenantContext ctx = TenantContextHolder.require(request);
        return Response.ok().bodyJson(hookService.testHook(ctx, collection, hookDefinition));
    }
}
