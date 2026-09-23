package models;

import org.apache.logging.log4j.core.config.plugins.validation.constraints.Required;

import java.util.List;
import java.util.Map;

public record HookDefinition(
        String id,

        @Required
        String name,

        String description,

        @Required
        String collection,

        @Required
        HookEvent event,

        @Required
        String url,

        String method,
        Integer timeoutMs,
        String secret,
        Map<String, String> headers,
        Boolean enabled,
        Integer priority,
        Boolean includeSchema,
        Boolean failOpen,
        Boolean applyToAllCollections,
        List<String> targetCollections,

        /*
         * Additional incoming request headers this hook wants to see in the envelope, on top of
         * the fixed non-sensitive allowlist. Opt-in per hook because every entry is sent to an
         * externally configured URL; blocked headers are rejected on save.
         */
        List<String> forwardHeaders,

        /*
         * Whether this beforeRequest hook also guards the file routes
         * (/api/collections/{collection}/{id}/files/{field}[/{fileId}]). Opt-in and off by
         * default: an existing hook was written for collection and auth routes only, and a guard
         * that rejects what it does not know would otherwise block every download after an
         * upgrade. Costs one hook roundtrip per file request.
         */
        Boolean includeFileRoutes
) {
    public boolean appliesToAllCollections() {
        return Boolean.TRUE.equals(applyToAllCollections);
    }

    public boolean includesFileRoutes() {
        return Boolean.TRUE.equals(includeFileRoutes);
    }

    public List<String> forwardHeadersOrEmpty() {
        return forwardHeaders != null ? forwardHeaders : List.of();
    }

    public List<String> targetCollectionsOrEmpty() {
        return targetCollections != null ? targetCollections : List.of();
    }

    public boolean matchesCollectionScope(String collection) {
        if (event != HookEvent.beforeRequest) {
            return true;
        }
        if (appliesToAllCollections()) {
            return true;
        }
        if (collection == null || collection.isBlank()) {
            return false;
        }
        return targetCollectionsOrEmpty().contains(collection);
    }
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public int priorityOrDefault() {
        return priority != null ? priority : 100;
    }

    public int timeoutOrDefault() {
        if (timeoutMs != null && timeoutMs > 0) {
            return timeoutMs;
        }
        return event.isBlocking() ? 5000 : 30000;
    }

    public String methodOrDefault() {
        return method != null && !method.isBlank() ? method.toUpperCase() : "POST";
    }

    public boolean failOpenOrDefault() {
        return Boolean.TRUE.equals(failOpen);
    }
}
