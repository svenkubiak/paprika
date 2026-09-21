package hooks;

import constants.RequestAttributes;
import io.mangoo.routing.bindings.Request;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects the {@link HookInvocation}s of a request so the request log can report how much of the
 * total time was spent waiting for hooks. Blocking hooks run inside the request thread, so a plain
 * list guarded by synchronization is enough.
 */
public final class HookTelemetry {
    private HookTelemetry() {
    }

    public static void record(Request request, HookInvocation invocation) {
        if (request == null || invocation == null) {
            return;
        }

        List<HookInvocation> invocations = request.getAttribute(RequestAttributes.HOOK_INVOCATIONS);
        if (invocations == null) {
            invocations = Collections.synchronizedList(new ArrayList<>());
            request.addAttribute(RequestAttributes.HOOK_INVOCATIONS, invocations);
        }
        invocations.add(invocation);
    }

    public static List<HookInvocation> invocations(Request request) {
        if (request == null) {
            return List.of();
        }

        List<HookInvocation> invocations = request.getAttribute(RequestAttributes.HOOK_INVOCATIONS);
        if (invocations == null) {
            return List.of();
        }

        synchronized (invocations) {
            return List.copyOf(invocations);
        }
    }

    /** Host and port of a hook URL - the part that identifies the target without exposing paths. */
    public static String target(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        try {
            URI uri = URI.create(url.trim());
            if (uri.getHost() == null) {
                return null;
            }
            return uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
