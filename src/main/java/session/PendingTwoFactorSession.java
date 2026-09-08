package session;

import io.mangoo.routing.bindings.Request;
import io.mangoo.routing.bindings.Session;

import java.util.Optional;

public final class PendingTwoFactorSession {
    public static final String USER_ID_KEY = "paprika.pending2faUserId";
    public static final String SECRET_KEY = "paprika.pending2faSecret";

    private PendingTwoFactorSession() {
    }

    public static Optional<String> getUserId(Request request) {
        Session session = request.getSession();
        if (session == null) {
            return Optional.empty();
        }

        String userId = session.get(USER_ID_KEY);
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(userId);
    }

    public static Optional<String> getPendingSecret(Request request) {
        Session session = request.getSession();
        if (session == null) {
            return Optional.empty();
        }

        String secret = session.get(SECRET_KEY);
        if (secret == null || secret.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(secret);
    }

    public static void setPendingLogin(Request request, String userId) {
        Session session = request.getSession();
        if (session == null) {
            return;
        }

        session.put(USER_ID_KEY, userId);
        session.remove(SECRET_KEY);
    }

    public static void setPendingSetup(Request request, String secret) {
        Session session = request.getSession();
        if (session == null) {
            return;
        }

        session.put(SECRET_KEY, secret);
    }

    public static void clear(Request request) {
        Session session = request.getSession();
        if (session == null) {
            return;
        }

        session.remove(USER_ID_KEY);
        session.remove(SECRET_KEY);
    }
}
