package utils;

import constants.SettingKeys;
import io.mangoo.routing.bindings.Request;
import org.apache.commons.lang3.StringUtils;

import java.net.InetAddress;

/**
 * Resolves the client IP for the request log.
 * <p>
 * An IP address identifies a person, so it is only kept when the operator asked for it. The
 * {@code truncated} mode is the middle ground the GDPR's data minimisation expects: enough to
 * recognise an abusive network, too little to single out a household - IPv4 loses its last octet,
 * IPv6 everything below the /48 prefix.
 * <p>
 * The address is read from the proxy headers only: Paprika is meant to run behind a reverse proxy,
 * and the socket peer would be that proxy, not the caller. Those headers are therefore whatever
 * the caller put in them: nginx's {@code $proxy_add_x_forwarded_for} appends the peer to the list
 * the client sent, and the leftmost entry - the one that names the original client - is the one
 * the client wrote. Everything here treats the value as untrusted input accordingly, and a value
 * that is not an IP address is dropped rather than interpreted.
 */
public final class ClientIps {
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String REAL_IP = "X-Real-IP";

    private ClientIps() {
    }

    public static String resolve(Request request, String mode) {
        if (request == null || mode == null || SettingKeys.CLIENT_IP_OFF.equalsIgnoreCase(mode.trim())) {
            return null;
        }

        String candidate = firstForwardedFor(request.getHeader(FORWARDED_FOR));
        if (candidate == null) {
            candidate = StringUtils.trimToNull(request.getHeader(REAL_IP));
        }

        // Parsed once, for both modes: "full" must not write an arbitrary header value into the
        // log either, and neither mode has anything to store when the header names no address.
        InetAddress address = parseLiteral(candidate);
        if (address == null) {
            return null;
        }

        return SettingKeys.CLIENT_IP_FULL.equalsIgnoreCase(mode.trim())
                ? candidate
                : truncate(address);
    }

    private static String firstForwardedFor(String header) {
        String value = StringUtils.trimToNull(header);
        if (value == null) {
            return null;
        }

        int comma = value.indexOf(',');
        return StringUtils.trimToNull(comma >= 0 ? value.substring(0, comma) : value);
    }

    /** IPv4 to /24, IPv6 to /48; an unparsable value is dropped rather than stored raw. */
    public static String truncate(String address) {
        InetAddress parsed = parseLiteral(address);
        return parsed == null ? null : truncate(parsed);
    }

    private static String truncate(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return (bytes[0] & 0xFF) + "." + (bytes[1] & 0xFF) + "." + (bytes[2] & 0xFF) + ".0";
        }

        StringBuilder prefix = new StringBuilder();
        for (int group = 0; group < 3; group++) {
            int value = ((bytes[group * 2] & 0xFF) << 8) | (bytes[group * 2 + 1] & 0xFF);
            prefix.append(Integer.toHexString(value)).append(':');
        }
        return prefix.append(':').toString();
    }

    /**
     * The address a header value names, or {@code null} when it names none.
     * <p>
     * {@link InetAddress#ofLiteral} parses and never resolves, which is the entire point.
     * {@code InetAddress.getByName} - what this used to call - hands anything that is not a
     * literal to the resolver, so a header the caller writes turned into one blocking DNS lookup
     * per logged request, against a nameserver the caller chose, in the response path. What came
     * back was then truncated and stored as the client's address, even though the caller had
     * never been there.
     */
    private static InetAddress parseLiteral(String value) {
        if (value == null) {
            return null;
        }

        try {
            return InetAddress.ofLiteral(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
