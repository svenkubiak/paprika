package utils;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The arithmetic behind the source binding of an API key. Everything here decides whether a
 * credential is accepted, so the edge cases - the wrong address family, a prefix of 0, an
 * IPv4-mapped IPv6 caller - are the point of the test rather than an afterthought.
 */
class CidrsTest {

    @Test
    void normalizesBareAddressesToSingleHostRanges() {
        assertThat(Cidrs.normalize("10.200.0.1"), equalTo("10.200.0.1/32"));
        assertThat(Cidrs.normalize("2a01:4f8:c17:c74c::1"), equalTo("2a01:4f8:c17:c74c::1/128"));
    }

    @Test
    void masksHostBitsAndCompressesIpv6() {
        assertThat(Cidrs.normalize("10.200.0.7/24"), equalTo("10.200.0.0/24"));
        assertThat(Cidrs.normalize("  192.168.13.200/16  "), equalTo("192.168.0.0/16"));
        assertThat(Cidrs.normalize("2A01:4F8:C17:C74C::1/48"), equalTo("2a01:4f8:c17::/48"));
        assertThat(Cidrs.normalize("::1/128"), equalTo("::1/128"));
        assertThat(Cidrs.normalize("::/0"), equalTo("::/0"));
    }

    @Test
    void rejectsAnythingThatIsNotACidrRange() {
        assertThrows(IllegalArgumentException.class, () -> Cidrs.normalize("not-an-address"));
        assertThrows(IllegalArgumentException.class, () -> Cidrs.normalize("10.200.0.0/33"));
        assertThrows(IllegalArgumentException.class, () -> Cidrs.normalize("10.200.0.0/-1"));
        assertThrows(IllegalArgumentException.class, () -> Cidrs.normalize("10.200.0.0/abc"));
        assertThrows(IllegalArgumentException.class, () -> Cidrs.normalize("2a01:4f8::/129"));
        assertThrows(IllegalArgumentException.class, () -> Cidrs.normalize("10.200.0.300/24"));
        assertThrows(IllegalArgumentException.class, () -> Cidrs.normalize(""));
        assertThrows(IllegalArgumentException.class, () -> Cidrs.normalize(null));
    }

    /**
     * A hostname would have to be resolved to be useful, which is exactly what must not happen in
     * the authentication path - and {@code InetAddress.getByName} would have done it silently.
     */
    @Test
    void neverResolvesAHostname() {
        assertThrows(IllegalArgumentException.class, () -> Cidrs.normalize("localhost"));
        assertThrows(IllegalArgumentException.class, () -> Cidrs.normalize("example.com/32"));
    }

    @Test
    void normalizeAllDropsBlanksAndDuplicatesButKeepsOrder() {
        List<String> normalized = Cidrs.normalizeAll(
                List.of("10.200.0.0/24", "  ", "10.200.0.99/24", "127.0.0.1"));

        assertThat(normalized, contains("10.200.0.0/24", "127.0.0.1/32"));
        assertThat(Cidrs.normalizeAll(null), empty());
        assertThat(Cidrs.normalizeAll(List.of()), empty());
    }

    @Test
    void matchesIpv4WithinAndOutsideTheRange() {
        List<String> range = List.of("10.200.0.0/24");

        assertThat(Cidrs.contains(range, address("10.200.0.1")), is(true));
        assertThat(Cidrs.contains(range, address("10.200.0.255")), is(true));
        assertThat(Cidrs.contains(range, address("10.200.1.1")), is(false));
        assertThat(Cidrs.contains(range, address("203.0.113.7")), is(false));
    }

    @Test
    void matchesIpv6WithinAndOutsideTheRange() {
        List<String> range = List.of("2a01:4f8:c17:c74c::/64");

        assertThat(Cidrs.contains(range, address("2a01:4f8:c17:c74c::1")), is(true));
        assertThat(Cidrs.contains(range, address("2a01:4f8:c17:c74c:ffff:ffff:ffff:ffff")), is(true));
        assertThat(Cidrs.contains(range, address("2a01:4f8:c17:c74d::1")), is(false));
        assertThat(Cidrs.contains(List.of("2a01:4f8:c17:c74c::1/128"), address("2a01:4f8:c17:c74c::2")),
                is(false));
    }

    /** A /24 of IPv4 says nothing about an IPv6 caller, and guessing otherwise would widen it. */
    @Test
    void doesNotMatchAcrossAddressFamilies() {
        assertThat(Cidrs.contains(List.of("0.0.0.0/0"), address("2a01:4f8::1")), is(false));
        assertThat(Cidrs.contains(List.of("::/0"), address("10.200.0.1")), is(false));
    }

    /** A dual-stack listener must not change whether an IPv4 caller matches an IPv4 range. */
    @Test
    void treatsAnIpv4MappedCallerAsIpv4() throws Exception {
        InetAddress mapped = InetAddress.getByAddress(new byte[]{
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 10, (byte) 200, 0, 1});

        assertThat(Cidrs.contains(List.of("10.200.0.0/24"), mapped), is(true));
    }

    @Test
    void emptyListAndUnknownAddressMatchNothing() {
        assertThat(Cidrs.contains(List.of(), address("10.200.0.1")), is(false));
        assertThat(Cidrs.contains(null, address("10.200.0.1")), is(false));
        assertThat(Cidrs.contains(List.of("10.200.0.0/24"), null), is(false));
    }

    @Test
    void anyOfSeveralRangesIsEnough() {
        List<String> ranges = List.of("10.200.0.0/24", "2a01:4f8:c17:c74c::/64", "203.0.113.9/32");

        assertThat(Cidrs.contains(ranges, address("203.0.113.9")), is(true));
        assertThat(Cidrs.contains(ranges, address("2a01:4f8:c17:c74c::99")), is(true));
        assertThat(Cidrs.contains(ranges, address("203.0.113.10")), is(false));
    }

    private static InetAddress address(String literal) {
        return InetAddress.ofLiteral(literal);
    }
}
