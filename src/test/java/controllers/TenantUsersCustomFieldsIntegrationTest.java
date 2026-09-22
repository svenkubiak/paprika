package controllers;

import auth.TenantContext;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionDefinition;
import models.TenantDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import utils.AdminTestUtils;
import utils.TenantTestUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * The admin user editor writes and reads the fields a tenant added to its own users schema, not
 * just the core fields. Without this the custom part of the schema is invisible in the admin UI and
 * can only be maintained through {@code /api/collections/users}.
 */
@ExtendWith({TestRunner.class})
class TenantUsersCustomFieldsIntegrationTest {

    @Test
    void customFieldsRoundTripThroughTheAdminApi() {
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        CollectionDefinition original = collections.findDefinition(ctx, "users");

        try {
            AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();
            addCustomFields(cookies, original.id());

            String usersUrl = "/api/meta/tenants/" + tenant.id() + "/users";

            TestResponse create = AdminTestUtils.postWithAdminCookies(
                    usersUrl,
                    cookies,
                    """
                    {
                      "username": "custom-fields-user",
                      "password": "secret-password-123",
                      "email": "custom@example.com",
                      "displayName": "Custom Fields",
                      "loyaltyPoints": 42
                    }
                    """,
                    "application/json");

            assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));
            assertThat(create.getContent(), containsString("\"displayName\":\"Custom Fields\""));
            assertThat(create.getContent(), containsString("\"loyaltyPoints\":42"));
            // Credentials must not leak just because the response is no longer a fixed whitelist.
            assertThat(create.getContent(), not(containsString("passwordHash")));
            assertThat(create.getContent(), not(containsString("passwordSalt")));

            String userId = extractId(create.getContent());

            TestResponse list = AdminTestUtils.getWithAdminCookies(usersUrl, cookies);
            assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(list.getContent(), containsString("\"displayName\":\"Custom Fields\""));

            TestResponse update = AdminTestUtils.patchWithAdminCookies(
                    usersUrl + "/" + userId,
                    cookies,
                    """
                    {
                      "username": "custom-fields-user",
                      "email": "custom@example.com",
                      "displayName": "Renamed",
                      "loyaltyPoints": null
                    }
                    """,
                    "application/json");

            assertThat(update.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(update.getContent(), containsString("\"displayName\":\"Renamed\""));
            // A null value clears the field rather than storing a null.
            assertThat(update.getContent(), not(containsString("loyaltyPoints")));

            AdminTestUtils.deleteWithAdminCookies(usersUrl + "/" + userId, cookies);
        } finally {
            collections.replaceDefinition(ctx, original);
        }
    }

    @Test
    void unknownAndReadOnlyFieldsAreRejected() {
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        CollectionDefinition original = collections.findDefinition(ctx, "users");

        try {
            AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();
            addCustomFields(cookies, original.id());

            String usersUrl = "/api/meta/tenants/" + tenant.id() + "/users";

            TestResponse unknown = AdminTestUtils.postWithAdminCookies(
                    usersUrl,
                    cookies,
                    """
                    {
                      "username": "custom-fields-unknown",
                      "password": "secret-password-123",
                      "notInSchema": "x"
                    }
                    """,
                    "application/json");
            assertThat(unknown.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
            assertThat(unknown.getContent(), containsString("notInSchema"));

            TestResponse wrongType = AdminTestUtils.postWithAdminCookies(
                    usersUrl,
                    cookies,
                    """
                    {
                      "username": "custom-fields-type",
                      "password": "secret-password-123",
                      "loyaltyPoints": "not-a-number"
                    }
                    """,
                    "application/json");
            assertThat(wrongType.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
            assertThat(wrongType.getContent(), containsString("loyaltyPoints"));

            TestResponse readOnly = AdminTestUtils.postWithAdminCookies(
                    usersUrl,
                    cookies,
                    """
                    {
                      "username": "custom-fields-readonly",
                      "password": "secret-password-123",
                      "createdAt": "2020-01-01T00:00:00Z"
                    }
                    """,
                    "application/json");
            assertThat(readOnly.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
            assertThat(readOnly.getContent(), containsString("read-only"));

            TestResponse credentials = AdminTestUtils.postWithAdminCookies(
                    usersUrl,
                    cookies,
                    """
                    {
                      "username": "custom-fields-credentials",
                      "password": "secret-password-123",
                      "passwordHash": "injected"
                    }
                    """,
                    "application/json");
            assertThat(credentials.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
            assertThat(credentials.getContent(), containsString("read-only"));
        } finally {
            collections.replaceDefinition(ctx, original);
        }
    }

    private void addCustomFields(AdminTestUtils.AdminCookies cookies, String definitionId) {
        TestResponse patch = AdminTestUtils.patchWithAdminCookies(
                "/api/meta/collections/users/" + definitionId,
                cookies,
                """
                {
                  "name": "users",
                  "fields": [
                    {"name": "displayName", "type": "STRING", "required": false, "nullable": true},
                    {"name": "loyaltyPoints", "type": "NUMBER", "required": false, "nullable": true}
                  ],
                  "indexes": []
                }
                """,
                "application/json");

        assertThat(patch.getStatusCode(), equalTo(StatusCodes.OK));
    }

    private String extractId(String json) {
        String marker = "\"id\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new AssertionError("No id in response: " + json);
        }
        start += marker.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
