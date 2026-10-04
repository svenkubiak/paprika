package controllers;

import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.AdminTestUtils;

import java.net.HttpCookie;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Bean Validation failures answer through mangoo's request handler, not the controller. Uses
 * {@code /api/auth/register} because its tenant check runs after validation, keeping the two apart.
 */
@ExtendWith({TestRunner.class})
class BeanValidationResponseIntegrationTest {

    /** Guards application.validation.passthrough in config.yaml; off, mangoo answers with an HTML page. */
    @Test
    void constraintViolationRespondsWithJsonNotTheDefaultHtmlPage() {
        TestResponse response = TestRequest.post("/api/auth/register")
                .withStringBody("{\"tenant\":\"default\",\"username\":\"\",\"password\":\"\"}")
                .withContentType("application/json")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), not(containsString("<!DOCTYPE html>")));
        assertThat(response.getContent(), containsString("\"errors\""));
        assertThat(response.getContent(), containsString("Username is required"));
        assertThat(response.getContent(), containsString("Password is required"));
    }

    /** The DTO constraints must be @NotBlank, not @NotEmpty. */
    @Test
    void whitespaceOnlyValueIsRejectedByValidation() {
        TestResponse response = TestRequest.post("/api/auth/register")
                .withStringBody("{\"tenant\":\"default\",\"username\":\"   \",\"password\":\"secret-password-123\"}")
                .withContentType("application/json")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("Username is required"));
    }

    /**
     * Without a JSON content type mangoo binds the DTO to null, and @Valid does not cascade into
     * null, so only @NotNull prevents an NPE. The error key is the Java parameter name, so only the
     * message is asserted.
     */
    @Test
    void missingRequestBodyIsRejectedInsteadOfFailingInTheController() {
        TestResponse response = TestRequest.post("/api/auth/register").execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("Request body is required"));
    }

    /** The services dereference the DTO without a null check, so a missing body must be a 400. */
    @Test
    void adminEndpointWithoutABodyIsRejectedRatherThanFailingInTheService() {
        HttpCookie auth = AdminTestUtils.loginAsAdmin();

        TestResponse response = TestRequest.post("/api/admin/profile/2fa/setup")
                .withCookie(auth)
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("Request body is required"));
    }

    /** Otherwise validation messages would let an unauthenticated caller probe a protected endpoint. */
    @Test
    void authenticationIsCheckedBeforeValidation() {
        TestResponse response = TestRequest.post("/api/admin/profile/2fa/setup").execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        assertThat(response.getContent(), containsString("Unauthorized"));
    }

    /**
     * mangoo keys errors by field name, so two failing constraints race for the message. {@code
     * TenantDto.slug} pairs @NotNull with @Pattern so only one can fail for a given input.
     */
    @Test
    void twoConstraintsOnOneFieldDoNotRaceForTheMessage() {
        HttpCookie auth = AdminTestUtils.loginAsAdmin();

        TestResponse empty = TestRequest.post("/api/meta/tenants")
                .withCookie(auth)
                .withStringBody("{\"name\":\"Race\",\"slug\":\"\"}")
                .withContentType("application/json")
                .execute();

        assertThat(empty.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(empty.getContent(), containsString("Slug must be one or more"));
        assertThat(empty.getContent(), not(containsString("Slug is required")));

        TestResponse missing = TestRequest.post("/api/meta/tenants")
                .withCookie(auth)
                .withStringBody("{\"name\":\"Race\"}")
                .withContentType("application/json")
                .execute();

        assertThat(missing.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(missing.getContent(), containsString("Slug is required"));
        assertThat(missing.getContent(), not(containsString("Slug must be one or more")));
    }

    /** Validation must not become an oracle for endpoints that deliberately answer uniformly. */
    @Test
    void emptyJsonObjectStillReachesTheController() {
        TestResponse response = TestRequest.post("/api/auth/password/forgot")
                .withStringBody("{}")
                .withContentType("application/json")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), containsString("\"success\":true"));
    }
}
