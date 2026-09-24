package services;

import auth.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import constants.CollectionName;
import constants.SystemCollections;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.mangoo.utils.JsonUtils;
import io.undertow.util.StatusCodes;
import models.TenantDefinition;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.AdminTestUtils;
import utils.DbUtils;

import java.net.HttpCookie;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * The dashboard signals that replaced the two hardcoded "all green" indicators. Each one is only
 * worth showing if it can actually report a problem, so that is what is pinned here.
 */
@ExtendWith({TestRunner.class})
class InstanceHealthSignalsIntegrationTest {

    /**
     * Counted on an own tenant, not on the default one: the rest of the suite writes into that
     * log all the time, so the expected number would depend on what ran before.
     */
    @Test
    void serverErrorsAreCountedForTheLastDayAndOnlyFrom500Up() {
        TenantService tenantService = Application.getInstance(TenantService.class);
        RequestLogService requestLogService = Application.getInstance(RequestLogService.class);
        TenantDefinition tenant = tenantService.create("Error Count", "error-count-test");

        try {
            TenantContext ctx = TenantContext.guest(tenant.id(), tenant.databaseName());
            assertThat(requestLogService.countServerErrors24h(ctx), equalTo(0L));

            logEntry(ctx, 500, Instant.now());
            logEntry(ctx, 503, Instant.now().minus(23, ChronoUnit.HOURS));
            // A client being a client is not a server error, and yesterday is not today.
            logEntry(ctx, 404, Instant.now());
            logEntry(ctx, 200, Instant.now());
            logEntry(ctx, 500, Instant.now().minus(25, ChronoUnit.HOURS));

            assertThat(requestLogService.countServerErrors24h(ctx), equalTo(2L));
        } finally {
            tenantService.deleteWithCascade(tenant.id());
        }
    }

    @Test
    void serverErrorCountIsZeroWithoutATenantContext() {
        RequestLogService requestLogService = Application.getInstance(RequestLogService.class);

        assertThat(requestLogService.countServerErrors24h(null), equalTo(0L));
        assertThat(requestLogService.countServerErrors24h(TenantContext.guest(null, null)), equalTo(0L));
    }

    /**
     * Reproduces what a restored archive does: duplicates are already in place when the unique
     * index is created, so the index is not created and nothing enforces uniqueness any more.
     * Until now the only trace of that was a line in the startup log.
     */
    @Test
    void aTenantThatCannotCarryTheUniqueIndexIsReported() {
        TenantService tenantService = Application.getInstance(TenantService.class);
        TenantDatabaseResolver resolver = Application.getInstance(TenantDatabaseResolver.class);
        TenantDefinition tenant = tenantService.create("Degraded Index", "degraded-index-test");

        try {
            assertThat(tenantService.degradedIndexDatabaseNames(), not(hasItem(tenant.databaseName())));

            var metaCollections = resolver.tenantDatabase(tenant.databaseName())
                    .getCollection(CollectionName.META_COLLECTIONS);
            metaCollections.dropIndex("name_unique");
            metaCollections.insertOne(new Document().append("id", DbUtils.id()).append("name", "duplicate"));
            metaCollections.insertOne(new Document().append("id", DbUtils.id()).append("name", "duplicate"));

            tenantService.initializeTenantDatabase(tenant);

            assertThat(tenantService.degradedIndexDatabaseNames(), hasItem(tenant.databaseName()));

            // And it clears again once the duplicates are gone - the warning must not outlive
            // the problem until the next restart.
            metaCollections.deleteOne(new Document("name", "duplicate"));
            tenantService.initializeTenantDatabase(tenant);

            assertThat(tenantService.degradedIndexDatabaseNames(), not(hasItem(tenant.databaseName())));
        } finally {
            tenantService.deleteWithCascade(tenant.id());
        }
    }

    @Test
    void deletingATenantRetractsItsIndexWarning() {
        TenantService tenantService = Application.getInstance(TenantService.class);
        TenantDatabaseResolver resolver = Application.getInstance(TenantDatabaseResolver.class);
        TenantDefinition tenant = tenantService.create("Degraded Gone", "degraded-gone-test");

        var metaCollections = resolver.tenantDatabase(tenant.databaseName())
                .getCollection(CollectionName.META_COLLECTIONS);
        metaCollections.dropIndex("name_unique");
        metaCollections.insertOne(new Document().append("id", DbUtils.id()).append("name", "duplicate"));
        metaCollections.insertOne(new Document().append("id", DbUtils.id()).append("name", "duplicate"));
        tenantService.initializeTenantDatabase(tenant);
        assertThat(tenantService.degradedIndexDatabaseNames(), hasItem(tenant.databaseName()));

        tenantService.deleteWithCascade(tenant.id());

        assertThat(tenantService.degradedIndexDatabaseNames(), not(hasItem(tenant.databaseName())));
    }

    /**
     * The payload the dashboard is built from. The two removed fields are asserted on explicitly:
     * they were hardcoded to true and must not come back as something the UI can render green.
     */
    @Test
    void bootstrapPayloadCarriesTheSignalsAndNoLongerTheHardcodedOnes() {
        HttpCookie authentication = AdminTestUtils.loginAsAdmin();

        TestResponse response = TestRequest.get("/admin/bootstrap")
                .withCookie(authentication)
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));

        JsonNode payload = parse(response);
        JsonNode stats = payload.get("stats");
        JsonNode warnings = payload.get("warnings");

        assertThat(stats.has("serverErrors24h"), is(true));
        assertThat(stats.has("connected"), is(false));
        assertThat(stats.has("healthy"), is(false));
        assertThat(warnings, notNullValue());
        assertThat(warnings.get("mailDependentTenants").isArray(), is(true));
        assertThat(warnings.get("degradedIndexTenants").isArray(), is(true));
    }

    /**
     * The test instance has an SMTP host, so the mail warning has to stay silent no matter which
     * recovery features a tenant switches on.
     */
    @Test
    void noMailWarningWhileTheInstanceHasAnSmtpHost() {
        TenantService tenantService = Application.getInstance(TenantService.class);
        TenantDefinition tenant = tenantService.create("Mail Warning", "mail-warning-test");
        tenantService.update(
                tenant.id(), null, null, null, null, true, true, null, null, null, null);

        try {
            HttpCookie authentication = AdminTestUtils.loginAsAdmin();
            TestResponse response = TestRequest.get("/admin/bootstrap")
                    .withCookie(authentication)
                    .execute();

            JsonNode warnings = parse(response).get("warnings");

            assertThat(warnings.get("mailDependentTenants").isEmpty(), is(true));
        } finally {
            tenantService.deleteWithCascade(tenant.id());
        }
    }

    private static JsonNode parse(TestResponse response) {
        try {
            return JsonUtils.getMapper().readTree(response.getContent());
        } catch (Exception e) {
            throw new AssertionError("Bootstrap payload is not valid JSON: " + e.getMessage(), e);
        }
    }

    private static void logEntry(TenantContext ctx, int statusCode, Instant timestamp) {
        Application.getInstance(TenantDatabaseResolver.class)
                .tenantMetaCollection(ctx, SystemCollections.REQUEST_LOGS)
                .insertOne(new Document()
                        .append("id", DbUtils.id())
                        .append("type", "request")
                        .append("method", "GET")
                        .append("url", "/api/collections/whatever")
                        .append("statusCode", statusCode)
                        .append("timestamp", timestamp.toString()));
    }
}
