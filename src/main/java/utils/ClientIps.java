package utils;

import constants.SettingKeys;
import io.mangoo.routing.bindings.Request;
import org.apache.commons.lang3.StringUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Resolves the client IP for the request log.
 * <p>
 * An IP address identifies a person, so it is only kept when the operator asked for it. The
 * {@code truncated} mode is the middle ground the GDPR's data minimisation expects: enough to
 * recognise an abusive network, too little to single out a household - IPv4 loses its last octet,
 * IPv6 everything below the /48 prefix.
 * <p>
 * The address is read from the proxy headers only: Paprika is meant to run behind a reverse proxy,
 * and the socket peer would be that proxy, not the caller.
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

        String address = firstForwardedFor(request.getHeader(FORWARDED_FOR));
        if (address == null) {
            address = StringUtils.trimToNull(request.getHeader(REAL_IP));
        }
        if (address == null) {
            return null;
        }

        if (SettingKeys.CLIENT_IP_FULL.equalsIgnoreCase(mode.trim())) {
            return address;
        }

        return truncate(address);
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
        try {
            byte[] bytes = InetAddress.getByName(address).getAddress();
            if (bytes.length == 4) {
                return (bytes[0] & 0xFF) + "." + (bytes[1] & 0xFF) + "." + (bytes[2] & 0xFF) + ".0";
            }

            StringBuilder prefix = new StringBuilder();
            for (int group = 0; group < 3; group++) {
                int value = ((bytes[group * 2] & 0xFF) << 8) | (bytes[group * 2 + 1] & 0xFF);
                prefix.append(Integer.toHexString(value)).append(':');
            }
            return prefix.append(':').toString();
        } catch (UnknownHostException | ArrayIndexOutOfBoundsException e) {
            return null;
        }
    }
}
