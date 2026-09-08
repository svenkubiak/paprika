package session;

import io.mangoo.routing.bindings.Request;
import io.mangoo.routing.bindings.Session;

import java.util.Optional;

public final class AdminTenantSession {
    public static final String SESSION_KEY = "paprika.activeTenantId";
    public static final String AUTO_DEFAULT_APPLIED_KEY = "paprika.autoDefaultTenantApplied";

    private AdminTenantSession() {
    }

    public static Optional<String> getActiveTenantId(Request request) {
        Session session = request.getSession();
        if (session == null) {
            return Optional.empty();
        }

        String tenantId = session.get(SESSION_KEY);
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(tenantId);
    }

    public static void setActiveTenantId(Request request, String tenantId) {
        Session session = request.getSession();
        if (session == null) {
            return;
        }

        session.put(SESSION_KEY, tenantId);
    }

    public static void clearActiveTenantId(Request request) {
        Session session = request.getSession();
        if (session == null) {
            return;
        }

        session.remove(SESSION_KEY);
    }

    public static void clearAutoDefaultApplied(Request request) {
        Session session = request.getSession();
        if (session == null) {
            return;
        }

        session.remove(AUTO_DEFAULT_APPLIED_KEY);
    }

    public static void markAutoDefaultApplied(Request request) {
        Session session = request.getSession();
        if (session == null) {
            return;
        }

        session.put(AUTO_DEFAULT_APPLIED_KEY, "true");
    }

    public static boolean isAutoDefaultApplied(Request request) {
        Session session = request.getSession();
        if (session == null) {
            return false;
        }

        return "true".equals(session.get(AUTO_DEFAULT_APPLIED_KEY));
    }

    public static void resetTenantSelection(Request request) {
        clearActiveTenantId(request);
        clearAutoDefaultApplied(request);
    }
}
