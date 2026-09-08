package controllers;

import auth.TenantContext;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionDefinition;
import models.FieldDefinition;
import models.IndexDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import utils.AdminTestUtils;
import utils.TenantTestUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

@ExtendWith({TestRunner.class})
class MetaUsersSchemaIntegrationTest {

    @Test
    void editingUsersSchemaPreservesCoreFieldsAndUsernameIndex() {
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        CollectionDefinition original = collections.findDefinition(ctx, "users");

        try {
            AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

            // Attempt to wipe the core fields and index, adding only a custom field.
            TestResponse patch = AdminTestUtils.patchWithAdminCookies(
                    "/api/meta/collections/users/" + original.id(),
                    cookies,
                    """
                    {
                      "name": "users",
                      "fields": [
                        {"name": "displayName", "type": "STRING", "required": false, "nullable": true}
                      ],
                      "indexes": []
                    }
                    """,
                    "application/json"
            );

            assertThat(patch.getStatusCode(), equalTo(StatusCodes.OK));

            CollectionDefinition after = collections.findDefinition(ctx, "users");
            var names = after.fields().stream().map(FieldDefinition::name).toList();
            assertThat(names.contains("username"), is(true));
            assertThat(names.contains("email"), is(true));
            assertThat(names.contains("role"), is(true));
            assertThat(names.contains("password"), is(true));
            assertThat(names.contains("displayName"), is(true));
            assertThat(names.contains("passwordHash"), is(false));
            assertThat(names.contains("passwordSalt"), is(false));

            boolean hasUsernameIndex = after.indexes().stream()
                    .map(IndexDefinition::name)
                    .anyMatch("username_unique"::equals);
            assertThat(hasUsernameIndex, is(true));
        } finally {
            collections.replaceDefinition(ctx, original);
        }
    }
}
