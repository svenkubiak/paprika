package services;

import auth.AuthContext;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionRules;
import models.TokenPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.TenantTestUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ExtendWith({TestRunner.class})
class AuthServiceTest {

    @Test
    void userFromRefreshTokenParsesIssuedRefreshToken() {
        AuthService authService = Application.getInstance(AuthService.class);
        TokenPair tokens = authService.createTokenPair(AuthContext.of("user-123", "user", "tenant-id"));

        assertThat(authService.userFromRefreshToken(tokens.refreshToken()).isPresent(), is(true));
    }

    @Test
    void bearerAccessTokenIsAcceptedByAuthFilter() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("jwt-parse-user", null, "secret-password-123");

        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody("jwt-parse-user", "secret-password-123"))
                .withContentType("application/json")
                .execute();

        assertThat(login.getStatusCode(), is(StatusCodes.OK));

        String accessToken = extractJsonString(login.getContent(), "accessToken");
        TenantTestUtils.seedCollection("posts_jwt_parse", new CollectionRules(null, null, "auth", null, null, "owner"));

        TestResponse create = TestRequest.post("/api/collections/posts_jwt_parse")
                .withHeader("Authorization", "Bearer " + accessToken)
                .withStringBody("{\"title\":\"hello\"}")
                .withContentType("application/json")
                .execute();

        assertThat(create.getStatusCode(), is(StatusCodes.CREATED));
    }

    private String extractJsonString(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("Missing " + field + " in: " + json);
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
