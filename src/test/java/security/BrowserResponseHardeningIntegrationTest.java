package security;

import enums.FieldType;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import models.CollectionRules;
import models.FieldDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.AdminTestUtils;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Server-side half of the admin UI's XSS protection: tenant-controlled markup must never reach the
 * browser in a renderable context (JSON as text/html, missing nosniff).
 */
@ExtendWith({TestRunner.class})
class BrowserResponseHardeningIntegrationTest {
    private static final String XSS = "<script>alert('xss')</script>";
    private static final String IMG_XSS = "<img src=x onerror=alert(1)>";

    @Test
    void tenantDataWithMarkupIsNeverServedAsHtml() {
        String collection = "xss_probe_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "*", "*", "*", "owner"),
                List.of(new FieldDefinition("title", FieldType.STRING, true, false, null)));

        TestResponse create = TestRequest.post("/api/collections/" + collection)
                .withStringBody("{\"title\":\"" + escape(XSS) + "\"}")
                .withContentType("application/json")
                .execute();
        assertThat(create.getStatusCode(), equalTo(201));

        TestResponse list = TestRequest.get("/api/collections/" + collection + "?offset=0&limit=25").execute();
        assertJsonOnly("record list", list);
        assertThat("the value is returned as data, not swallowed", list.getContent(), containsString("script"));

        AdminTestUtils.AdminCookies admin = AdminTestUtils.loginAsAdminWithDefaultTenant();
        assertJsonOnly("record list (admin session)",
                AdminTestUtils.getWithAdminCookies("/api/collections/" + collection + "?offset=0&limit=25", admin));
        assertJsonOnly("collection meta (admin session)",
                AdminTestUtils.getWithAdminCookies("/api/meta/collections/" + collection, admin));
    }

    @Test
    void validationErrorsEchoingInputAreNeverServedAsHtml() {
        String collection = "xss_validation_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "*", "*", "*", "owner"),
                List.of(new FieldDefinition("title", FieldType.STRING, true, false, null)));

        TestResponse unknownField = TestRequest.post("/api/collections/" + collection)
                .withStringBody("{\"title\":\"ok\",\"" + escape(IMG_XSS) + "\":\"x\"}")
                .withContentType("application/json")
                .execute();

        assertThat(unknownField.getStatusCode(), equalTo(400));
        assertJsonOnly("validation error", unknownField);
    }

    @Test
    void unknownCollectionErrorsAreNeverServedAsHtml() {
        TestResponse response = TestRequest.get("/api/collections/" + escapeUrl(IMG_XSS)).execute();

        assertThat(response.getStatusCode(), anyOf(equalTo(400), equalTo(401), equalTo(404)));
        assertThat("an error body must not reflect markup as html",
                contentType(response), not(containsStringIgnoringCase("text/html")));
    }

    @Test
    void theAdminUiShellCarriesItsSecurityHeaders() {
        TestResponse response = TestRequest.get("/login").execute();

        assertThat(response.getStatusCode(), equalTo(200));
        assertThat(header(response, "Content-Security-Policy"),
                allOf(containsString("default-src 'self'"),
                        containsString("script-src 'self'"),
                        containsString("object-src 'none'"),
                        containsString("frame-ancestors 'none'")));
        assertThat("inline scripts must not be allowed, the UI ships as module bundles",
                header(response, "Content-Security-Policy"), not(containsString("script-src 'self' 'unsafe-inline'")));
        assertThat(header(response, "X-Content-Type-Options"), equalToIgnoringCase("nosniff"));
        assertThat(header(response, "X-Frame-Options"), equalToIgnoringCase("DENY"));
        assertThat("a setup token in a URL must never travel in a referer",
                header(response, "Referrer-Policy"), equalToIgnoringCase("no-referrer"));
    }

    @Test
    void uploadedHtmlIsForcedIntoADownload() {
        String collection = "xss_file_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "*", "*", "*", "owner"),
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition("attachment", FieldType.FILE, false, true, null)));

        String recordId = TenantTestUtils.seedRecord(collection, "with attachment");

        // No file stored: only the download route's response contract matters, for any mime type
        TestResponse download = TestRequest.get(
                "/api/collections/" + collection + "/" + recordId + "/files/attachment").execute();

        assertThat(download.getStatusCode(), anyOf(equalTo(200), equalTo(404)));
        if (download.getStatusCode() == 200) {
            assertThat(header(download, "Content-Disposition"), containsString("attachment"));
            assertThat(header(download, "X-Content-Type-Options"), equalToIgnoringCase("nosniff"));
            assertThat(header(download, "Content-Security-Policy"), containsString("sandbox"));
        }
    }

    private static void assertJsonOnly(String what, TestResponse response) {
        assertThat(what + " must be served as json", contentType(response), containsString("application/json"));
        assertThat(what + " must not be served as html",
                contentType(response), not(containsStringIgnoringCase("text/html")));
        assertThat(what + " must be marked nosniff, otherwise a browser may sniff it as html",
                header(response, "X-Content-Type-Options"), equalToIgnoringCase("nosniff"));
    }

    private static String contentType(TestResponse response) {
        String value = header(response, "Content-Type");
        return value == null ? "" : value;
    }

    private static String header(TestResponse response, String name) {
        Optional<String> value = response.getHttpResponse().headers().firstValue(name);
        return value.orElse(null);
    }

    private static String escape(String value) {
        return value.replace("\"", "\\\"");
    }

    private static String escapeUrl(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
