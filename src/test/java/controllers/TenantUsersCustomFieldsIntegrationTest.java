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
            // Credentials must not leak now that the response is no longer a fixed whitelist
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

    /** Users are written by TenantUserService, not the record service, and need the same normalization. */
    @Test
    void temporalCustomFieldsAreNormalizedOnCreateAndUpdate() {
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        CollectionDefinition original = collections.findDefinition(ctx, "users");

        try {
            AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();
            TestResponse patch = AdminTestUtils.patchWithAdminCookies(
                    "/api/meta/collections/users/" + original.id(),
                    cookies,
                    """
                    {
                      "name": "users",
                      "fields": [
                        {"name": "memberSince", "type": "DATETIME", "required": false, "nullable": true},
                        {"name": "shiftStart", "type": "TIME", "required": false, "nullable": true}
                      ],
                      "indexes": []
                    }
                    """,
                    "application/json");
            assertThat(patch.getStatusCode(), equalTo(StatusCodes.OK));

            String usersUrl = "/api/meta/tenants/" + tenant.id() + "/users";
            TestResponse create = AdminTestUtils.postWithAdminCookies(
                    usersUrl,
                    cookies,
                    """
                    {
                      "username": "temporal-custom-user",
                      "password": "secret-password-123",
                      "memberSince": "2026-10-03T11:30:00+02:00",
                      "shiftStart": "08:00"
                    }
                    """,
                    "application/json");

            assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));
            assertThat(create.getContent(), containsString("\"memberSince\":\"2026-10-03T09:30:00.000Z\""));
            assertThat(create.getContent(), containsString("\"shiftStart\":\"08:00:00\""));

            String userId = extractId(create.getContent());
            TestResponse update = AdminTestUtils.patchWithAdminCookies(
                    usersUrl + "/" + userId,
                    cookies,
                    """
                    {
                      "username": "temporal-custom-user",
                      "memberSince": "2026-10-04T00:15:00+01:00"
                    }
                    """,
                    "application/json");

            assertThat(update.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(update.getContent(), containsString("\"memberSince\":\"2026-10-03T23:15:00.000Z\""));

            TestResponse fraction = AdminTestUtils.patchWithAdminCookies(
                    usersUrl + "/" + userId,
                    cookies,
                    """
                    {
                      "username": "temporal-custom-user",
                      "shiftStart": "08:00:00.5"
                    }
                    """,
                    "application/json");
            assertThat(fraction.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));

            AdminTestUtils.deleteWithAdminCookies(usersUrl + "/" + userId, cookies);
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
