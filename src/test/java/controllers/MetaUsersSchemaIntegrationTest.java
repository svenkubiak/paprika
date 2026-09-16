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

    @Test
    void addingFieldWithAdminUiPayloadKeepsSingleUsernameIndex() {
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        CollectionDefinition original = collections.findDefinition(ctx, "users");

        try {
            AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

            // The schema editor sends the whole definition back and derives index names from the
            // field name, so the unique username index arrives as "idx_username" rather than
            // under its canonical name.
            TestResponse patch = AdminTestUtils.patchWithAdminCookies(
                    "/api/meta/collections/users/" + original.id(),
                    cookies,
                    """
                    {
                      "name": "users",
                      "fields": [
                        {"name": "username", "type": "STRING", "required": true, "nullable": false},
                        {"name": "email", "type": "EMAIL", "required": false, "nullable": true},
                        {"name": "role", "type": "STRING", "required": false, "nullable": true},
                        {"name": "password", "type": "STRING", "required": true, "nullable": false},
                        {"name": "displayName", "type": "STRING", "required": false, "nullable": true}
                      ],
                      "indexes": [
                        {
                          "name": "idx_username",
                          "unique": true,
                          "fields": [{"field": "username", "direction": "ASC"}]
                        }
                      ]
                    }
                    """,
                    "application/json"
            );

            assertThat(patch.getStatusCode(), equalTo(StatusCodes.OK));

            CollectionDefinition after = collections.findDefinition(ctx, "users");
            var indexNames = after.indexes().stream().map(IndexDefinition::name).toList();
            assertThat(indexNames, equalTo(java.util.List.of("username_unique")));

            var usernameIndexes = Application.getInstance(services.TenantDatabaseResolver.class)
                    .tenantDataCollection(ctx, "users")
                    .listIndexes()
                    .into(new java.util.ArrayList<>())
                    .stream()
                    .filter(index -> index.get("key", org.bson.Document.class).containsKey("username"))
                    .map(index -> index.getString("name"))
                    .toList();
            assertThat(usernameIndexes, equalTo(java.util.List.of("username_unique")));
        } finally {
            collections.replaceDefinition(ctx, original);
        }
    }
}
