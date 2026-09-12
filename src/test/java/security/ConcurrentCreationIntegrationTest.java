package security;

import com.mongodb.client.model.Filters;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import models.CollectionRules;
import models.TenantDefinition;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import services.TenantService;
import utils.AdminTestUtils;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Uniqueness has to hold under concurrency, not only in the check that precedes an insert.
 * <p>
 * "Is this name still free? Then insert it" are two operations, and two requests arriving together
 * both pass the check. Whether that matters depends on what the name means: a duplicate collection
 * definition is a correctness problem with security impact, because the name is what every request
 * resolves its rules through - with two definitions under one name, an admin editing the rules may
 * be editing the one that is not being served.
 * <p>
 * A note on the expected statuses: rejected attempts are required to be "not created" rather than a
 * specific code, because mangoo I/O 10.12.1 sporadically answers 500 on concurrent requests (its
 * attachment key is built lazily on an unsynchronized static field). That fails closed and never
 * produces a second creation, which is what is asserted here.
 */
@ExtendWith({TestRunner.class})
class ConcurrentCreationIntegrationTest {
    private static final int PARALLEL_ATTEMPTS = 8;

    private static AdminTestUtils.AdminCookies admin;

    @BeforeAll
    static void setUp() {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        Application.getInstance(TenantService.class).update(
                tenant.id(), null, null, null, true, null, null, null, null, null, null);
        admin = AdminTestUtils.loginAsAdminWithDefaultTenant();
    }

    @Test
    void aUsernameCanOnlyBeRegisteredOnce() throws Exception {
        String username = "race-user-" + DbUtils.id().substring(0, 8);

        List<Integer> statuses = inParallel(index -> TestRequest.post("/api/auth/register")
                .withStringBody("{\"tenant\":\"default\",\"username\":\"" + username
                        + "\",\"password\":\"race-password-1234\"}")
                .withContentType("application/json")
                .execute()
                .getStatusCode());

        assertThat("exactly one registration may be created: " + statuses, created(statuses), equalTo(1L));
        assertThat("and exactly one user may exist",
                Application.getInstance(TenantCollectionService.class)
                        .dataCollection(TenantTestUtils.defaultTenantContext(), "users")
                        .countDocuments(Filters.eq("username", username)),
                equalTo(1L));
    }

    @Test
    void aCollectionNameCanOnlyBeCreatedOnce() throws Exception {
        String collection = "race_coll_" + DbUtils.id().substring(0, 8);

        List<Integer> statuses = inParallel(index -> AdminTestUtils.postWithAdminCookies(
                        "/api/meta/collections/" + collection, admin,
                        "{\"fields\":[{\"name\":\"title\",\"type\":\"STRING\",\"required\":true,\"nullable\":false}]}",
                        "application/json")
                .getStatusCode());

        assertThat("exactly one creation may succeed: " + statuses, created(statuses), equalTo(1L));
        assertThat("two definitions under one name would make the effective rules non deterministic",
                Application.getInstance(TenantCollectionService.class)
                        .metaCollections(TenantTestUtils.defaultTenantContext())
                        .countDocuments(Filters.eq("name", collection)),
                equalTo(1L));
    }

    @Test
    void aTenantSlugCanOnlyBeCreatedOnce() throws Exception {
        String slug = "race-tenant-" + DbUtils.id().substring(0, 8);

        List<Integer> statuses = inParallel(index -> AdminTestUtils.postWithAdminCookies(
                        "/api/meta/tenants", admin,
                        "{\"name\":\"Race\",\"slug\":\"" + slug + "\"}", "application/json")
                .getStatusCode());

        assertThat("exactly one tenant may be created: " + statuses, created(statuses), equalTo(1L));
        assertThat(Application.getInstance(TenantService.class).listAll().stream()
                        .filter(tenant -> slug.equals(tenant.slug())).count(),
                equalTo(1L));
    }

    /**
     * Concurrent updates of the same record are last write wins by design - there is no optimistic
     * locking for plain field updates. What must hold is that the record stays one consistent
     * document from one of the writers, rather than a mix of several.
     */
    @Test
    void concurrentRecordUpdatesLeaveOneConsistentRecord() throws Exception {
        String collection = "race_rec_" + DbUtils.id().substring(0, 8);
        TenantTestUtils.seedCollection(collection, new CollectionRules("*", "*", "*", "*", "*", "owner"));
        String recordId = TenantTestUtils.seedRecord(collection, "original");

        List<Integer> statuses = inParallel(index -> TestRequest
                .patch("/api/collections/" + collection + "/" + recordId)
                .withStringBody("{\"title\":\"update-" + index + "\"}")
                .withContentType("application/json")
                .execute()
                .getStatusCode());

        assertThat("no update may fail with a server error: " + statuses,
                statuses.stream().filter(status -> status >= 500).count(), equalTo(0L));

        Document record = Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), collection)
                .find(Filters.eq("id", recordId))
                .first();

        assertThat(record, notNullValue());
        assertThat("the surviving value must be one of the writes, never a merge of them",
                record.getString("title"), startsWith("update-"));
    }

    // ---------------------------------------------------------------------------------------

    private static long created(List<Integer> statuses) {
        return statuses.stream().filter(status -> status >= 200 && status < 300).count();
    }

    /** Releases all attempts at once, so they genuinely overlap instead of running in sequence. */
    private static List<Integer> inParallel(IndexedCall call) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_ATTEMPTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < PARALLEL_ATTEMPTS; index++) {
                int current = index;
                futures.add(executor.submit(() -> {
                    start.await();
                    return call.apply(current);
                }));
            }

            start.countDown();

            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface IndexedCall {
        Integer apply(int index) throws Exception;
    }
}
