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
 * The leftmost X-Forwarded-For entry is caller text, so only IP literals are parsed: resolving a name
 * would be a blocking DNS lookup against a caller-chosen nameserver, logged as the caller's address.
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

    /** localhost resolves everywhere without a nameserver, so it reliably shows that names are not resolved. */
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

    /** Asserts the absence of a DNS lookup: a thousand parses without I/O finish well under a second. */
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
