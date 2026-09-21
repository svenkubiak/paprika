package controllers;

import auth.TenantContext;
import com.sun.net.httpserver.HttpServer;
import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionRules;
import models.FieldDefinition;
import models.HookDefinition;
import models.HookEvent;
import models.TenantDefinition;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.HookService;
import services.TenantCollectionService;
import services.TenantService;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * A blocking hook decides *that* an operation is rejected and may pick the status of its rejection,
 * but it must not be able to make a rejection look like a success (or a redirect) to the client,
 * and its envelope vocabulary ("continue") must not leak into the API response.
 */
@ExtendWith({TestRunner.class})
class HookRejectionResponseIntegrationTest {

    @Test
    void statusTwoHundredFromTheHookIsRejectedAndLogged() throws IOException {
        CapturingAppender logs = attachLogCapture();
        try {
            TestResponse response = rejectWith("""
                    {"continue": false, "error": {"status": 200, "message": "nope"}}
                    """, false);

            assertThat("a rejection must never arrive as a success",
                    response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
            assertThat(logs.messages(), hasItem(containsString("200")));
            assertThat(logs.messages(), hasItem(containsString("400")));
        } finally {
            logs.detach();
        }
    }

    @Test
    void statusOutsideTheAllowedRangeFallsBackToTheDefault() throws IOException {
        for (String status : List.of("302", "0", "700", "\"abc\"", "404.5", "null")) {
            TestResponse response = rejectWith("""
                    {"continue": false, "error": {"status": %s, "message": "nope"}}
                    """.formatted(status), false);

            assertThat("invalid status " + status + " must fall back to the default",
                    response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        }
    }

    @Test
    void validStatusIsPassedThroughUnchanged() throws IOException {
        for (int status : List.of(403, 409, 422, 503)) {
            TestResponse response = rejectWith("""
                    {"continue": false, "error": {"status": %d, "message": "nope"}}
                    """.formatted(status), false);

            assertThat(response.getStatusCode(), equalTo(status));
            assertThat(response.getContent(), containsString("\"message\":\"nope\""));
        }
    }

    @Test
    void stringErrorBecomesAnErrorObjectWithoutProtocolFields() throws IOException {
        TestResponse response = rejectWith("""
                {"continue": false, "error": "trial_expired"}
                """, false);

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent().replace(" ", ""), containsString("\"error\":\"trial_expired\""));
        assertThat(response.getContent(), not(containsString("continue")));
    }

    @Test
    void objectErrorIsDeliveredVerbatimWithoutProtocolFields() throws IOException {
        TestResponse response = rejectWith("""
                {"continue": false, "error": {"status": 422, "message": "title too short", "field": "title"}}
                """, false);

        assertThat(response.getStatusCode(), equalTo(422));
        String body = response.getContent().replace(" ", "");
        assertThat(body, containsString("\"message\":\"titletooshort\""));
        assertThat(body, containsString("\"field\":\"title\""));
        assertThat(body, containsString("\"status\":422"));
        assertThat(response.getContent(), not(containsString("continue")));
    }

    @Test
    void rejectionWithoutErrorFieldUsesTheDefaultMessage() throws IOException {
        TestResponse response = rejectWith("""
                {"continue": false}
                """, false);

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("Hook rejected request"));
        assertThat(response.getContent(), not(containsString("continue")));
    }

    /**
     * A rejection carried by a 4xx response is a rejection, not a hook outage - otherwise failOpen
     * would wave through exactly the write the hook refused.
     */
    @Test
    void rejectionDeliveredWithHttp403StaysARejectionEvenWithFailOpen() throws IOException {
        TestResponse response = rejectWith("""
                {"continue": false, "error": {"status": 403, "message": "not allowed"}}
                """, 403, true);

        assertThat(response.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
        assertThat(response.getContent(), containsString("not allowed"));
    }

    private TestResponse rejectWith(String hookResponse, boolean failOpen) throws IOException {
        return rejectWith(hookResponse, 200, failOpen);
    }

    private TestResponse rejectWith(String hookResponse, int hookStatus, boolean failOpen) throws IOException {
        String collection = seedCollection();
        HttpServer server = startHookServer(hookResponse, hookStatus);
        allowWebhookHost(server);

        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        HookDefinition hook = hook(collection, server, failOpen);
        collections.insertHook(ctx, hook);

        try {
            return TestRequest.post("/api/collections/" + collection)
                    .withStringBody("{\"title\":\"triggers the hook\"}")
                    .withContentType("application/json")
                    .execute();
        } finally {
            collections.deleteHook(ctx, hook.id());
            server.stop(0);
            resetWebhookAllowlist();
        }
    }

    private static String seedCollection() {
        String collection = "notes_hookreject_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "*", "*", "*", "owner"),
                List.of(new FieldDefinition("title", FieldType.STRING, true, false, null)));
        return collection;
    }

    private static HookDefinition hook(String collection, HttpServer server, boolean failOpen) {
        return new HookDefinition(
                DbUtils.id(),
                "hook-rejection-test",
                null,
                collection,
                HookEvent.beforeCreate,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/hook",
                null,
                null,
                "test-secret",
                null,
                true,
                null,
                null,
                failOpen,
                null,
                null,
                null);
    }

    private static HttpServer startHookServer(String responseBody, int status) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static void allowWebhookHost(HttpServer server) {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        Application.getInstance(TenantService.class).update(
                tenant.id(), null, null, null, null, null, null, null, null, null,
                List.of("127.0.0.1:" + server.getAddress().getPort()));
    }

    private static void resetWebhookAllowlist() {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        Application.getInstance(TenantService.class).update(
                tenant.id(), null, null, null, null, null, null, null, null, null, List.of());
    }

    private static CapturingAppender attachLogCapture() {
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        Logger logger = (Logger) LogManager.getLogger(HookService.class);
        logger.addAppender(appender);
        return appender;
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = Collections.synchronizedList(new ArrayList<>());

        private CapturingAppender() {
            super("hook-rejection-log-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }

        private List<String> messages() {
            return new ArrayList<>(messages);
        }

        private void detach() {
            ((Logger) LogManager.getLogger(HookService.class)).removeAppender(this);
            stop();
        }
    }
}
