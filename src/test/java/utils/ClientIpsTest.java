package utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;

/**
 * What a value out of {@code X-Forwarded-For} may become in the request log.
 * <p>
 * The header is written by the caller - nginx appends the peer to the list the client sent, so
 * the leftmost entry, the one naming the original client, is the client's own text. This used to
 * go into {@code InetAddress.getByName}, which resolves anything that is not a literal: one
 * blocking DNS lookup per logged request against a nameserver the caller picks, and the address
 * that came back was then stored as if the caller had come from there. Only literals are parsed
 * now, and everything else is dropped.
 */
class ClientIpsTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "203.0.113.7,        203.0.113.0",
            "203.0.113.0,        203.0.113.0",
            "8.8.8.8,            8.8.8.0",
            "0.0.0.0,            0.0.0.0",
            "255.255.255.255,    255.255.255.0",
            "127.0.0.1,          127.0.0.0"
    })
    void anIpv4AddressKeepsItsNetworkAndLosesItsHost(String address, String expected) {
        assertThat(ClientIps.truncate(address), equalTo(expected));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "2001:db8:1234:5678::1,                    2001:db8:1234::",
            "2001:db8:1234:ffff:ffff:ffff:ffff:ffff,   2001:db8:1234::",
            "2001:db8:1234::,                          2001:db8:1234::",
            "::1,                                      0:0:0::",
            "fe80::1,                                  fe80:0:0::"
    })
    void anIpv6AddressIsCutToItsRoutingPrefix(String address, String expected) {
        assertThat(ClientIps.truncate(address), equalTo(expected));
    }

    /**
     * The defect. {@code localhost} resolves everywhere, without a network and without a
     * nameserver, so it is the one host name that used to come back as a stored address
     * ({@code 127.0.0.0}) with complete certainty. It has to be refused now, like any other name.
     */
    @Test
    void aResolvableHostNameIsNotAnAddressAndIsDropped() {
        assertThat(ClientIps.truncate("localhost"), nullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "example.com",
            "attacker-controlled.invalid",
            "not-an-ip",
            "203.0.113.7.example.com",
            "999.1.1.1",
            "0x7f.1",
            "203.0.113.7 with trailing text",
            "<script>alert(1)</script>",
            "'; DROP TABLE logs; --"
    })
    void anythingThatIsNotAnAddressIsDropped(String value) {
        assertThat(ClientIps.truncate(value), nullValue());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void aMissingValueIsDropped(String value) {
        assertThat(ClientIps.truncate(value), nullValue());
    }

    /**
     * Not a timing assertion on the truncation itself - it is a handful of shifts - but on the
     * absence of the lookup behind it. A resolver round trip is milliseconds even when it
     * succeeds and seconds when the nameserver stalls; a thousand parses that never leave the
     * process are comfortably under a second, and nothing that does I/O per call would be.
     */
    @Test
    void parsingNeverReachesTheResolver() {
        long started = System.nanoTime();
        for (int i = 0; i < 1_000; i++) {
            assertThat(ClientIps.truncate("host-" + i + ".example.com"), nullValue());
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertThat(elapsedMillis, lessThan(1_000L));
    }
}
