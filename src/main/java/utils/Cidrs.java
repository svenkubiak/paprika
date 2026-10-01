package utils;

import org.apache.commons.lang3.StringUtils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

// Deliberately CIDR only: hostnames or geo lookups would put an external dependency into the auth path.
// Ranges are parsed and canonicalised at write time; the request path only compares bytes.
public final class Cidrs {
    private static final int IPV4_BITS = 32;
    private static final int IPV6_BITS = 128;

    private static final int IPV4_MAPPED_PREFIX_LENGTH = 12;

    private Cidrs() {
    }

    // An invalid range throws instead of being dropped, which could open a key to unlisted networks
    public static String normalize(String value) {
        String trimmed = StringUtils.trimToNull(value);
        if (trimmed == null) {
            throw new IllegalArgumentException("A CIDR range must not be empty");
        }

        String literal = trimmed;
        Integer declaredPrefix = null;

        int slash = trimmed.indexOf('/');
        if (slash >= 0) {
            literal = trimmed.substring(0, slash).trim();
            String prefixPart = trimmed.substring(slash + 1).trim();
            try {
                declaredPrefix = Integer.valueOf(prefixPart);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "'" + trimmed + "' is not a valid CIDR range: '" + prefixPart
                                + "' is not a prefix length");
            }
        }

        // ofLiteral never resolves; getByName would send non-literals to DNS
        InetAddress address;
        try {
            address = InetAddress.ofLiteral(literal);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "'" + trimmed + "' is not a valid CIDR range: '" + literal + "' is not an IP address");
        }

        byte[] bytes = address.getAddress();
        int maxPrefix = bytes.length == 4 ? IPV4_BITS : IPV6_BITS;
        int prefix = declaredPrefix == null ? maxPrefix : declaredPrefix;
        if (prefix < 0 || prefix > maxPrefix) {
            throw new IllegalArgumentException(
                    "'" + trimmed + "' is not a valid CIDR range: the prefix length must be between 0 and "
                            + maxPrefix + " for " + (bytes.length == 4 ? "IPv4" : "IPv6"));
        }

        return format(mask(bytes, prefix), prefix);
    }

    public static List<String> normalizeAll(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.isBlank(value)) {
                continue;
            }
            normalized.add(normalize(value));
        }
        return List.copyOf(normalized);
    }

    // A null address matches nothing, so an unknown source fails closed
    public static boolean contains(List<String> cidrs, InetAddress address) {
        if (cidrs == null || cidrs.isEmpty() || address == null) {
            return false;
        }

        byte[] candidate = unmap(address.getAddress());
        for (String cidr : cidrs) {
            if (matches(cidr, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String cidr, byte[] candidate) {
        byte[] network;
        int prefix;
        try {
            int slash = cidr.indexOf('/');
            if (slash < 0) {
                return false;
            }
            network = InetAddress.ofLiteral(cidr.substring(0, slash)).getAddress();
            prefix = Integer.parseInt(cidr.substring(slash + 1));
        } catch (IllegalArgumentException e) {
            // A stored range that no longer parses must never widen the match
            return false;
        }

        // An IPv4 range never covers an IPv6 caller
        if (network.length != candidate.length) {
            return false;
        }

        int fullBytes = prefix / 8;
        int remainingBits = prefix % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (network[i] != candidate[i]) {
                return false;
            }
        }
        if (remainingBits == 0) {
            return true;
        }

        int mask = (0xFF << (8 - remainingBits)) & 0xFF;
        return (network[fullBytes] & mask) == (candidate[fullBytes] & mask);
    }

    /** Unmaps ::ffff:a.b.c.d itself rather than relying on Java returning an {@link Inet4Address}. */
    private static byte[] unmap(byte[] bytes) {
        if (bytes.length != 16) {
            return bytes;
        }

        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return bytes;
            }
        }
        if ((bytes[10] & 0xFF) != 0xFF || (bytes[11] & 0xFF) != 0xFF) {
            return bytes;
        }

        byte[] ipv4 = new byte[4];
        System.arraycopy(bytes, IPV4_MAPPED_PREFIX_LENGTH, ipv4, 0, 4);
        return ipv4;
    }

    private static byte[] mask(byte[] bytes, int prefix) {
        byte[] masked = bytes.clone();
        for (int i = 0; i < masked.length; i++) {
            int bitsBefore = i * 8;
            if (prefix >= bitsBefore + 8) {
                continue;
            }
            if (prefix <= bitsBefore) {
                masked[i] = 0;
            } else {
                masked[i] = (byte) (masked[i] & (0xFF << (8 - (prefix - bitsBefore))));
            }
        }
        return masked;
    }

    private static String format(byte[] bytes, int prefix) {
        return (bytes.length == 4 ? formatIpv4(bytes) : formatIpv6(bytes)) + "/" + prefix;
    }

    private static String formatIpv4(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                text.append('.');
            }
            text.append(bytes[i] & 0xFF);
        }
        return text.toString();
    }

    // RFC 5952 form; getHostAddress writes all eight groups, which operators would not recognise
    private static String formatIpv6(byte[] bytes) {
        int[] groups = new int[8];
        for (int i = 0; i < 8; i++) {
            groups[i] = ((bytes[i * 2] & 0xFF) << 8) | (bytes[i * 2 + 1] & 0xFF);
        }

        int bestStart = -1;
        int bestLength = 0;
        int currentStart = -1;
        int currentLength = 0;
        for (int i = 0; i < 8; i++) {
            if (groups[i] == 0) {
                if (currentStart < 0) {
                    currentStart = i;
                    currentLength = 0;
                }
                currentLength++;
                if (currentLength > bestLength) {
                    bestStart = currentStart;
                    bestLength = currentLength;
                }
            } else {
                currentStart = -1;
                currentLength = 0;
            }
        }
        // A single zero group is written out; "::" for one group is shorter but not canonical
        if (bestLength < 2) {
            bestStart = -1;
        }

        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (bestStart >= 0 && i == bestStart) {
                text.append("::");
                i += bestLength - 1;
                continue;
            }
            if (!text.isEmpty() && text.charAt(text.length() - 1) != ':') {
                text.append(':');
            }
            text.append(Integer.toHexString(groups[i]).toLowerCase(Locale.ROOT));
        }

        return text.toString();
    }
}
