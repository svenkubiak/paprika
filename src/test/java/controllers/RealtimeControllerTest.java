package controllers;

import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.TenantDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantUserService;
import services.UserService;
import utils.TenantTestUtils;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

@ExtendWith({TestRunner.class})
class RealtimeControllerTest {

    @Test
    void subscribeWithoutBearerReturnsUnauthorized() {
        TestResponse response = TestRequest.post("/api/realtime/subscribe")
                .withStringBody("{\"clientId\":\"missing\",\"subscriptions\":[\"trips\"]}")
                .withContentType("application/json")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
    }

    @Test
    void subscribeWithInvalidBearerReturnsUnauthorized() {
        TestResponse response = TestRequest.post("/api/realtime/subscribe")
                .withHeader("Authorization", "Bearer invalid-token")
                .withStringBody("{\"clientId\":\"missing\",\"subscriptions\":[\"trips\"]}")
                .withContentType("application/json")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
    }

    @Test
    void subscribeWithUnknownClientIdReturnsNotFound() {
        UserService userService = io.mangoo.core.Application.getInstance(UserService.class);
        userService.createUser("realtime-user", null, "secret-password-123");

        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody("realtime-user", "secret-password-123"))
                .withContentType("application/json")
                .execute();

        String accessToken = extractJsonString(login.getContent(), "accessToken");

        TestResponse response = TestRequest.post("/api/realtime/subscribe")
                .withHeader("Authorization", "Bearer " + accessToken)
                .withStringBody("{\"clientId\":\"00000000-0000-0000-0000-000000000000\",\"subscriptions\":[\"trips\"]}")
                .withContentType("application/json")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));
    }

    @Test
    void subscribeRejectsDeletedUserAccessToken() {
        UserService userService = io.mangoo.core.Application.getInstance(UserService.class);
        Map<String, Object> user = userService.createUser("deleted-realtime-user", null, "secret-password-123");

        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody("deleted-realtime-user", "secret-password-123"))
                .withContentType("application/json")
                .execute();
        String accessToken = extractJsonString(login.getContent(), "accessToken");

        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        io.mangoo.core.Application.getInstance(TenantUserService.class)
                .deleteUser(tenant, String.valueOf(user.get("id")));

        TestResponse response = TestRequest.post("/api/realtime/subscribe")
                .withHeader("Authorization", "Bearer " + accessToken)
                .withStringBody("{\"clientId\":\"00000000-0000-0000-0000-000000000000\",\"subscriptions\":[\"trips\"]}")
                .withContentType("application/json")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
    }

    private static String extractJsonString(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("Field not found: " + field);
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
