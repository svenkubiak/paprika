package controllers;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class AuthLinkBuilderTest {

    @Test
    void substitutesTokenPlaceholder() {
        assertThat(
                AuthController.buildLink("https://app.example.com/reset?ref={token}", "abc123"),
                equalTo("https://app.example.com/reset?ref=abc123"));
    }

    @Test
    void appendsTokenQueryParamWhenNoQuery() {
        assertThat(
                AuthController.buildLink("https://app.example.com/reset", "abc123"),
                equalTo("https://app.example.com/reset?token=abc123"));
    }

    @Test
    void appendsTokenWithAmpersandWhenQueryExists() {
        assertThat(
                AuthController.buildLink("https://app.example.com/reset?lang=en", "abc123"),
                equalTo("https://app.example.com/reset?lang=en&token=abc123"));
    }
}
