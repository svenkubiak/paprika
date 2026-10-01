package utils;

import constants.SettingKeys;
import io.mangoo.routing.bindings.Request;
import org.apache.commons.lang3.StringUtils;

import java.net.InetAddress;

// Client IP for the request log only (GDPR: truncated mode keeps IPv4 /24, IPv6 /48).
// Read from proxy headers, whose leftmost entry is client-written, so it is untrusted input:
// anything that is not an IP literal is dropped.
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

        // "full" must not log an arbitrary header value either
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

    // ofLiteral never resolves; getByName would turn a caller-written header into a blocking DNS
    // lookup against a nameserver the caller chose.
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
