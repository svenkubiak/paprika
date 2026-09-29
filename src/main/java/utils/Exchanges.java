package utils;

import io.mangoo.routing.bindings.Request;
import io.undertow.server.HttpServerExchange;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Access to the Undertow exchange behind a mangoo {@link Request}.
 * <p>
 * mangoo's request binding keeps the exchange in a private field and exposes no getter, so the
 * two things Paprika needs from it - the parsed multipart form and the peer of the TCP connection
 * - are only reachable reflectively. That lookup lives here once instead of in every caller, and
 * it fails soft: a {@code null} exchange means "this is not available", never an exception on a
 * request path.
 */
public final class Exchanges {
    private static final Field EXCHANGE_FIELD = resolveExchangeField();

    private Exchanges() {
    }

    private static Field resolveExchangeField() {
        try {
            Field field = Request.class.getDeclaredField("httpServerExchange");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    public static HttpServerExchange of(Request request) {
        if (request == null || EXCHANGE_FIELD == null) {
            return null;
        }

        try {
            return (HttpServerExchange) EXCHANGE_FIELD.get(request);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The peer of the TCP connection this request arrived on, or {@code null} when it cannot be
     * determined.
     * <p>
     * This is the one address in a request that the caller cannot choose. Every header that
     * claims to name a client - {@code X-Forwarded-For}, {@code X-Real-IP}, {@code Forwarded} -
     * is written by whoever sends the request, so none of them may decide an authorization
     * outcome. Behind a reverse proxy this is the proxy, not the client; what that means for the
     * one place it is used as a security input is spelled out at
     * {@link models.ApiKeyDefinition#allowedCidrs()}.
     */
    public static InetAddress peerAddress(Request request) {
        HttpServerExchange exchange = of(request);
        if (exchange == null) {
            return null;
        }

        InetSocketAddress source = exchange.getSourceAddress();
        return source == null ? null : source.getAddress();
    }
}
