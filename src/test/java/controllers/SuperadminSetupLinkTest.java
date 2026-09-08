package controllers;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

class SuperadminSetupLinkTest {

    @Test
    void buildsAbsoluteLinkFromHostAndProto() {
        assertThat(
                SuperadminController.setupLink("paprika.example.com", "https", "abc"),
                equalTo("https://paprika.example.com/setup#token=abc"));
    }

    @Test
    void keepsHostPortAndHonoursForwardedProto() {
        assertThat(
                SuperadminController.setupLink("paprika.example.com:8443", "http", "abc"),
                equalTo("http://paprika.example.com:8443/setup#token=abc"));
    }

    @Test
    void defaultsToHttpsWhenProtoMissing() {
        assertThat(
                SuperadminController.setupLink("paprika.example.com", null, "abc"),
                equalTo("https://paprika.example.com/setup#token=abc"));
    }

    @Test
    void returnsNullWhenHostUnknown() {
        assertThat(SuperadminController.setupLink(null, "https", "abc"), nullValue());
        assertThat(SuperadminController.setupLink("", "https", "abc"), nullValue());
    }
}
