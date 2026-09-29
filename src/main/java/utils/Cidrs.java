package utils;

import org.apache.commons.lang3.StringUtils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Parsing, normalisation and matching of CIDR ranges - the only form in which Paprika expresses
 * "this address, or one of these addresses".
 * <p>
 * Deliberately nothing else: no hostnames, no wildcards, no geo lookup. All three would need to
 * be resolved at request time and would thereby put an external dependency - and its latency and
 * failure modes - into the authentication path. A CIDR is decided by arithmetic on bytes that are
 * already in hand.
 * <p>
 * The work is split so that the expensive half happens once: {@link #normalize(String)} parses,
 * validates and rewrites a range into its canonical form when it is <em>stored</em>, and
 * {@link #contains(List, InetAddress)} only compares bytes when a request arrives.
 */
public final class Cidrs {
    private static final int IPV4_BITS = 32;
    private static final int IPV6_BITS = 128;

    /** The 80 zero bits plus 16 one bits that prefix an IPv4-mapped IPv6 address (::ffff:a.b.c.d). */
    private static final int IPV4_MAPPED_PREFIX_LENGTH = 12;

    private Cidrs() {
    }

    /**
     * The canonical form of one range, or an {@link IllegalArgumentException} naming what is
     * wrong with it. A range is never silently dropped: an operator who mistypes one of three
     * ranges would otherwise end up with a key that is open to a network they never listed.
     * <p>
     * A bare address is accepted as a convenience and becomes a single-host range ({@code /32}
     * or {@code /128}), and host bits below the prefix are masked off, so {@code 10.200.0.7/24}
     * is stored - and shown back - as {@code 10.200.0.0/24}. Both happen here, at write time:
     * the request path should compare bytes, not re-interpret text on every call.
     */
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

        // ofLiteral parses and never resolves. getByName would hand anything that is not a
        // literal to DNS, which would turn a stored range into a name lookup with a moving answer.
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

    /**
     * Normalises a whole list, dropping blank entries and duplicates while keeping the order the
     * operator entered. Throws on the first entry that is not a valid range.
     */
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

    /**
     * Whether {@code address} falls into any of the (already normalised) ranges.
     * <p>
     * An empty list answers {@code false}: this method says "the address is in the list", and the
     * caller decides what an empty list means. A {@code null} address is not in any range either,
     * which is what makes an unknown source fail closed rather than open.
     */
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

        // Families are compared as they are: a /24 of IPv4 does not cover an IPv6 caller, and
        // saying otherwise would be a guess about what the operator meant.
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

    /**
     * An IPv4-mapped IPv6 address ({@code ::ffff:a.b.c.d}) reduced to its four IPv4 bytes.
     * <p>
     * Java normally hands back an {@link Inet4Address} for those already, but a dual-stack
     * listener is exactly the place where that is worth not relying on: the same caller must not
     * match an IPv4 range on one socket configuration and fall through on another.
     */
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

    /**
     * RFC 5952 text for an IPv6 address: lower case, no leading zeroes, the longest run of zero
     * groups collapsed to {@code ::}. Java's own {@code getHostAddress} writes all eight groups
     * out, which is correct but turns a stored {@code 2a01:db8::/32} into something an operator
     * does not recognise as the range they typed.
     */
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
