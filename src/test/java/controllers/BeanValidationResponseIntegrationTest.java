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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Covers how a Bean Validation failure leaves the application, which is a contract of its own:
 * every {@code @Valid} controller parameter answers through mangoo's request handler rather than
 * through the controller body, so none of the per-endpoint tests can assert on it.
 *
 * <p>Uses {@code /api/auth/register} because it is reachable without a tenant in the body - the
 * controller's own tenant check runs after validation, which makes it easy to tell the two apart.
 */
@ExtendWith({TestRunner.class})
class BeanValidationResponseIntegrationTest {

    /**
     * Guards application.validation.passthrough in config.yaml. With it off, mangoo answers a
     * violation with its default 400 HTML page, which tells an API client nothing and ends up in
     * the admin UI verbatim.
     */
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

    /**
     * The DTO constraints are @NotBlank, not @NotEmpty: a whitespace-only username is not a
     * username. With @NotEmpty this passes validation and only fails later in the controller.
     */
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
     * Without a JSON content type mangoo binds the DTO parameter to null, so the @NotNull on the
     * parameter is what stands between that and an NPE in the controller. @Valid alone does not
     * cascade into null and would not catch this.
     *
     * <p>The response is {@code {"errors":{"registerDto":"..."}}} - the key of a parameter-level
     * violation is the Java parameter name, which is why the admin UI only prefixes keys when
     * there is more than one message. Asserted on the message alone so renaming the parameter
     * does not break this test.
     */
    @Test
    void missingRequestBodyIsRejectedInsteadOfFailingInTheController() {
        TestResponse response = TestRequest.post("/api/auth/register").execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("Request body is required"));
    }

    /**
     * The admin endpoints hand the DTO straight to the service behind them, which dereferences it
     * without a null check - a request without a body used to fail there with a 500 instead of
     * being rejected as a bad request.
     */
    @Test
    void adminEndpointWithoutABodyIsRejectedRatherThanFailingInTheService() {
        HttpCookie auth = AdminTestUtils.loginAsAdmin();

        TestResponse response = TestRequest.post("/api/admin/profile/2fa/setup")
                .withCookie(auth)
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("Request body is required"));
    }

    /**
     * Authentication still comes first: AdminAuthFilter ends the response before the request
     * handler ever validates, so an unauthenticated caller cannot use validation messages to
     * probe the shape of a protected endpoint.
     */
    @Test
    void authenticationIsCheckedBeforeValidation() {
        TestResponse response = TestRequest.post("/api/admin/profile/2fa/setup").execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        assertThat(response.getContent(), containsString("Unauthorized"));
    }

    /**
     * mangoo keys its error map by field name, so two constraints failing on the same field means
     * one message silently overwrites the other, decided by the iteration order of the violation
     * set. {@code TenantDto.slug} used to do exactly that on an empty slug; it now pairs @NotNull
     * with @Pattern so that only one of them can ever fail for a given input.
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

    /**
     * Validation runs against the request shape only, before the controller and before any
     * database access. It must not turn into an oracle for the endpoints that deliberately answer
     * uniformly - an empty JSON object has to reach the controller, not bounce off validation.
     */
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
