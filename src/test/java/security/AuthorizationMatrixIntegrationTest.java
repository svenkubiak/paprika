package security;

import auth.TenantContext;
import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import models.CollectionRules;
import models.FieldDefinition;
import models.FieldOptions;
import org.bson.Document;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import services.TenantCollectionService;
import services.UserService;
import utils.AdminTestUtils;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static com.mongodb.client.model.Filters.eq;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Executable specification of Paprika's access rules.
 * <p>
 * Instead of testing single scenarios, this walks the full matrix of
 * rule × caller × operation × record ownership and asserts the outcome of every combination.
 * Access rules are the central promise a BaaS makes to its users, so every combination is spelled
 * out here rather than left to be inferred from a handful of examples.
 * <p>
 * The record ownership dimension deliberately includes an <em>ownerless</em> record: records
 * created through the admin UI carry no owner field, and an {@code owner} rule used to evaluate to
 * true for them when the caller was a guest, because {@code null == null} compared equal
 * (see {@link rules.RuleEvaluator}). Any rule must deny access to a record it cannot prove
 * ownership of.
 * <p>
 * Assertions are written against the security outcome (granted vs. denied, and which records became
 * visible) rather than exact status codes, so that a denial may stay free to be expressed as 401,
 * 403 or 404 without weakening what is being verified. For denied writes the persisted state is
 * checked as well: a denial that still mutates data would not be a denial.
 */
@ExtendWith({TestRunner.class})
class AuthorizationMatrixIntegrationTest {
    private static final String OWNED_BY_A = "OWNED-BY-A";
    private static final String OWNED_BY_B = "OWNED-BY-B";
    private static final String OWNERLESS = "OWNERLESS";
    private static final String PASSWORD_A = "matrix-password-aaa-1";
    private static final String PASSWORD_B = "matrix-password-bbb-2";



    /** The rule values a user can configure per operation in the admin UI. */
    private enum Rule {
        LOCKED(null),
        PUBLIC("*"),
        AUTH("auth"),
        OWNER("owner");

        private final String value;

        Rule(String value) {
            this.value = value;
        }
    }

    /** Who is calling: unauthenticated, the owner, a different tenant user, or the admin UI. */
    private enum Caller {
        ANONYMOUS,
        USER_A,
        USER_B,
        ADMIN_COOKIE
    }

    private static Stream<Arguments> matrix() {
        List<Arguments> arguments = new ArrayList<>();
        for (Rule rule : Rule.values()) {
            for (Caller caller : Caller.values()) {
                arguments.add(Arguments.of(rule, caller));
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "rule={0} caller={1}")
    @MethodSource("matrix")
    void enforcesRuleForEveryOperationAndRecordOwnership(Rule rule, Caller caller) {
        Fixture fixture = new Fixture(rule, caller);

        // LIST first: it is the only read that must not leak records the caller may not view
        assertListVisibility(fixture);

        // VIEW before any write, so the record state is still the seeded one
        for (String title : List.of(OWNED_BY_A, OWNED_BY_B, OWNERLESS)) {
            assertView(fixture, title);
        }

        assertCreate(fixture);

        for (String title : List.of(OWNED_BY_A, OWNED_BY_B, OWNERLESS)) {
            assertUpdate(fixture, title);
        }

        // DELETE last, as it removes the records the other operations work on
        for (String title : List.of(OWNED_BY_A, OWNED_BY_B, OWNERLESS)) {
            assertDelete(fixture, title);
        }
    }

    /**
     * A file route reuses the record rules of its collection: downloading maps to VIEW and deleting
     * a file maps to UPDATE. Verified separately, as it is the second place a route parameter
     * decides which rule applies.
     */
    @ParameterizedTest(name = "caller={0}")
    @MethodSource("callers")
    void fileRoutesUseViewAndUpdateRules(Caller caller) {
        String collection = "matrix_files_" + DbUtils.id();
        // view is open, update is locked: a file download must pass, a file delete must not
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "*", null, "*", "owner"),
                schemaFields());

        String recordId = seedRecord(collection, OWNERLESS, null);
        Fixture fixture = new Fixture(collection, caller);

        TestResponse download = fixture.execute("GET", "/api/collections/" + collection + "/" + recordId + "/files/attachment", null);
        assertThat("file download follows the view rule and must not be denied",
                download.getStatusCode(), not(anyOf(equalTo(401), equalTo(403))));

        TestResponse delete = fixture.execute("DELETE", "/api/collections/" + collection + "/" + recordId + "/files/attachment", null);
        if (caller == Caller.ADMIN_COOKIE) {
            assertThat("the admin UI bypasses rules", delete.getStatusCode(), not(anyOf(equalTo(401), equalTo(403))));
        } else {
            assertThat("deleting a file follows the locked update rule and must be denied",
                    delete.getStatusCode(), anyOf(equalTo(401), equalTo(403)));
        }
    }

    private static Stream<Arguments> callers() {
        return Stream.of(Caller.values()).map(Arguments::of);
    }

    // ---------------------------------------------------------------------------------------
    // Expectations
    // ---------------------------------------------------------------------------------------

    /** Whether the caller may read or write a record with the given owner under the given rule. */
    private static boolean allows(Rule rule, Caller caller, String recordTitle) {
        if (caller == Caller.ADMIN_COOKIE) {
            // The admin UI session is the tenant operator and bypasses the collection rules
            return true;
        }

        return switch (rule) {
            case LOCKED -> false;
            case PUBLIC -> true;
            case AUTH -> caller != Caller.ANONYMOUS;
            // Ownership must be proven: an ownerless record belongs to nobody, never to the caller
            case OWNER -> switch (caller) {
                case USER_A -> OWNED_BY_A.equals(recordTitle);
                case USER_B -> OWNED_BY_B.equals(recordTitle);
                default -> false;
            };
        };
    }

    /** Whether the caller may create a record at all. */
    private static boolean allowsCreate(Rule rule, Caller caller) {
        if (caller == Caller.ADMIN_COOKIE) {
            return true;
        }

        return switch (rule) {
            case LOCKED -> false;
            case PUBLIC -> true;
            // owner also implies an authenticated caller, as the record is owned by whoever creates it
            case AUTH, OWNER -> caller != Caller.ANONYMOUS;
        };
    }

    /** Which of the seeded records a LIST must return for the caller. */
    private static List<String> visibleInList(Rule rule, Caller caller) {
        return Stream.of(OWNED_BY_A, OWNED_BY_B, OWNERLESS)
                .filter(title -> allows(rule, caller, title))
                .toList();
    }

    // ---------------------------------------------------------------------------------------
    // Assertions per operation
    // ---------------------------------------------------------------------------------------

    private void assertListVisibility(Fixture fixture) {
        TestResponse response = fixture.execute(
                "GET", "/api/collections/" + fixture.collection + "?offset=0&limit=25", null);

        List<String> expected = visibleInList(fixture.rule, fixture.caller);
        if (expected.isEmpty()) {
            // Either the request is denied outright or the list is scoped down to nothing,
            // both are a valid way of not leaking anything
            boolean denied = isDenied(response);
            boolean empty = response.getStatusCode() == 200 && response.getContent().contains("\"total\":0");
            assertThat("LIST must not expose records the caller may not view: " + response.getContent(),
                    denied || empty, is(true));
            return;
        }

        assertThat("LIST must be granted", isDenied(response), is(false));
        for (String title : List.of(OWNED_BY_A, OWNED_BY_B, OWNERLESS)) {
            if (expected.contains(title)) {
                assertThat("LIST must contain " + title, response.getContent(), containsString(title));
            } else {
                assertThat("LIST must not contain " + title, response.getContent(), not(containsString(title)));
            }
        }
    }

    private void assertView(Fixture fixture, String title) {
        String recordId = fixture.idOf(title);
        TestResponse response = fixture.execute(
                "GET", "/api/collections/" + fixture.collection + "/" + recordId, null);

        if (allows(fixture.rule, fixture.caller, title)) {
            assertThat("VIEW " + title + " must be granted", isDenied(response), is(false));
            assertThat(response.getContent(), containsString(title));
        } else {
            assertThat("VIEW " + title + " must be denied", isDenied(response), is(true));
            assertThat("a denied VIEW must not leak the record",
                    response.getContent(), not(containsString(title)));
        }
    }

    private void assertCreate(Fixture fixture) {
        String marker = "CREATED-" + DbUtils.id();
        TestResponse response = fixture.execute(
                "POST", "/api/collections/" + fixture.collection, "{\"title\":\"" + marker + "\"}");

        boolean expected = allowsCreate(fixture.rule, fixture.caller);
        if (expected) {
            assertThat("CREATE must be granted", isDenied(response), is(false));
            assertThat(findByTitle(fixture.collection, marker), notNullValue());
        } else {
            assertThat("CREATE must be denied", isDenied(response), is(true));
            assertThat("a denied CREATE must not persist anything",
                    findByTitle(fixture.collection, marker), nullValue());
        }
    }

    private void assertUpdate(Fixture fixture, String title) {
        String recordId = fixture.idOf(title);
        String marker = "UPDATED-" + DbUtils.id();
        TestResponse response = fixture.execute(
                "PATCH", "/api/collections/" + fixture.collection + "/" + recordId,
                "{\"title\":\"" + marker + "\"}");

        Document persisted = findById(fixture.collection, recordId);
        if (allows(fixture.rule, fixture.caller, title)) {
            assertThat("UPDATE " + title + " must be granted", isDenied(response), is(false));
            assertThat(persisted.getString("title"), equalTo(marker));
            // keep the fixture consistent for the operations that follow
            restoreTitle(fixture.collection, recordId, title);
        } else {
            assertThat("UPDATE " + title + " must be denied", isDenied(response), is(true));
            assertThat("a denied UPDATE must not mutate the record",
                    persisted.getString("title"), equalTo(title));
        }
    }

    private void assertDelete(Fixture fixture, String title) {
        String recordId = fixture.idOf(title);
        TestResponse response = fixture.execute(
                "DELETE", "/api/collections/" + fixture.collection + "/" + recordId, null);

        Document persisted = findById(fixture.collection, recordId);
        if (allows(fixture.rule, fixture.caller, title)) {
            assertThat("DELETE " + title + " must be granted", isDenied(response), is(false));
            assertThat(persisted, nullValue());
        } else {
            assertThat("DELETE " + title + " must be denied", isDenied(response), is(true));
            assertThat("a denied DELETE must not remove the record", persisted, notNullValue());
        }
    }

    private static boolean isDenied(TestResponse response) {
        int status = response.getStatusCode();
        // 404 counts as denied on purpose: rules hide the existence of a record rather than
        // confirming it with a 403
        return status == 401 || status == 403 || status == 404;
    }

    // ---------------------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------------------

    private final class Fixture {
        private final String collection;
        private final Rule rule;
        private final Caller caller;
        private final String token;
        private final AdminTestUtils.AdminCookies adminCookies;
        private final List<Document> records;

        private Fixture(Rule rule, Caller caller) {
            this.rule = rule;
            this.caller = caller;
            this.collection = "matrix_" + rule.name().toLowerCase(Locale.ROOT) + "_" + DbUtils.id();

            TenantTestUtils.seedCollection(
                    collection,
                    new CollectionRules(rule.value, rule.value, rule.value, rule.value, rule.value, "owner"),
                    schemaFields());

            seedRecord(collection, OWNED_BY_A, userId("matrix-user-a", PASSWORD_A));
            seedRecord(collection, OWNED_BY_B, userId("matrix-user-b", PASSWORD_B));
            seedRecord(collection, OWNERLESS, null);

            this.records = allRecords(collection);
            this.token = tokenFor(caller);
            this.adminCookies = caller == Caller.ADMIN_COOKIE
                    ? AdminTestUtils.loginAsAdminWithDefaultTenant()
                    : null;
        }

        /** Fixture for a pre-seeded collection, used by the file route test. */
        private Fixture(String collection, Caller caller) {
            this.collection = collection;
            this.rule = Rule.PUBLIC;
            this.caller = caller;
            this.records = List.of();
            this.token = tokenFor(caller);
            this.adminCookies = caller == Caller.ADMIN_COOKIE
                    ? AdminTestUtils.loginAsAdminWithDefaultTenant()
                    : null;
        }

        private String idOf(String title) {
            return records.stream()
                    .filter(record -> title.equals(record.getString("title")))
                    .map(record -> record.getString("id"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Missing seeded record: " + title));
        }

        private TestResponse execute(String method, String uri, String body) {
            TestResponse request = TestRequest.create(uri, method);
            if (body != null) {
                request = request.withStringBody(body).withContentType("application/json");
            }
            if (token != null) {
                request = request.withHeader("Authorization", "Bearer " + token);
            }
            if (adminCookies != null) {
                request = adminCookies.apply(request);
            }
            return request.execute();
        }
    }

    private String tokenFor(Caller caller) {
        return switch (caller) {
            case USER_A -> login("matrix-user-a", PASSWORD_A);
            case USER_B -> login("matrix-user-b", PASSWORD_B);
            case ANONYMOUS, ADMIN_COOKIE -> null;
        };
    }

    // ---------------------------------------------------------------------------------------
    // Data helpers
    // ---------------------------------------------------------------------------------------

    private static List<FieldDefinition> schemaFields() {
        return List.of(
                new FieldDefinition("title", FieldType.STRING, true, false, null),
                new FieldDefinition("attachment", FieldType.FILE, false, true, null),
                new FieldDefinition("owner", FieldType.RELATION, false, true, FieldOptions.forRelation("users")));
    }

    private static String seedRecord(String collection, String title, String ownerId) {
        String id = DbUtils.id();
        Document record = new Document().append("id", id).append("title", title);
        if (ownerId != null) {
            record.append("owner", ownerId);
        }
        collections().dataCollection(context(), collection).insertOne(record);
        return id;
    }

    private static List<Document> allRecords(String collection) {
        return collections().dataCollection(context(), collection).find().into(new ArrayList<>());
    }

    private static Document findById(String collection, String id) {
        return collections().dataCollection(context(), collection).find(eq("id", id)).first();
    }

    private static Document findByTitle(String collection, String title) {
        return collections().dataCollection(context(), collection).find(eq("title", title)).first();
    }

    private static void restoreTitle(String collection, String id, String title) {
        collections().dataCollection(context(), collection)
                .updateOne(eq("id", id), new Document("$set", new Document("title", title)));
    }

    private static TenantCollectionService collections() {
        return Application.getInstance(TenantCollectionService.class);
    }

    private static TenantContext context() {
        return TenantTestUtils.defaultTenantContext();
    }

    /** Creates the user on first use and returns its id. */
    private static String userId(String username, String password) {
        Document existing = collections().dataCollection(context(), "users")
                .find(eq("username", username))
                .first();
        if (existing != null) {
            return existing.getString("id");
        }
        return String.valueOf(Application.getInstance(UserService.class)
                .createUser(username, null, password)
                .get("id"));
    }

    private static String login(String username, String password) {
        userId(username, password);
        TestResponse response = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(username, password))
                .withContentType("application/json")
                .execute();

        String marker = "\"accessToken\":\"";
        int start = response.getContent().indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("Login failed for " + username + ": " + response.getContent());
        }
        start += marker.length();
        return response.getContent().substring(start, response.getContent().indexOf('"', start));
    }
}
