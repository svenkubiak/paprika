package utils;

import io.mangoo.routing.bindings.Request;
import io.undertow.server.HttpServerExchange;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;

// mangoo keeps the Undertow exchange in a private field without a getter, so it is read
// reflectively. Fails soft: null means unavailable, never an exception on a request path.
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

    // The one address the caller cannot choose, unlike X-Forwarded-For & co. Behind a proxy it is
    // the proxy; see ApiKeyDefinition#allowedCidrs() for the security implications.
    public static InetAddress peerAddress(Request request) {
        HttpServerExchange exchange = of(request);
        if (exchange == null) {
            return null;
        }

        InetSocketAddress source = exchange.getSourceAddress();
        return source == null ? null : source.getAddress();
    }
}
