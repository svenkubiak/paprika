package rules;

import auth.AuthContext;
import com.mongodb.client.model.Filters;
import enums.Role;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import utils.OwnerFieldUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * The rule semantics on unit level: the same promises the HTTP matrix verifies end to end, but
 * evaluated directly against the engine.
 * <p>
 * Two properties matter most here and are asserted explicitly for every combination:
 * <ul>
 *   <li><b>LIST and VIEW must agree.</b> They take different code paths -
 *       {@link RuleToMongoConverter} builds a query, {@link RuleEvaluator} decides in memory - and a
 *       disagreement between them is either a leak (list shows what view forbids) or a broken
 *       rule (list hides what view allows). Both real defects of that shape were of this kind.</li>
 *   <li><b>An unauthenticated caller never matches.</b> A rule must be satisfied by a proven
 *       identity, never by the absence of one.</li>
 * </ul>
 * Being free of I/O, these tests are also what mutation testing runs against
 * ({@code mvn -P mutation test}).
 */
class RuleSemanticsTest {
    private static final Bson DENIED = Filters.eq("id", "__paprika_denied__");
    private static final String OWNER_FIELD = "owner";

    private final RuleService ruleService = new RuleService();

    private static final AuthContext GUEST = AuthContext.guest();
    private static final AuthContext USER = AuthContext.of("user-1", Role.USER, "tenant-1");
    private static final AuthContext OTHER_USER = AuthContext.of("user-2", Role.USER, "tenant-1");

    private static Document ownedBy(String userId) {
        return new Document("id", "record-1").append(OWNER_FIELD, userId);
    }

    private static Document ownerless() {
        return new Document("id", "record-1");
    }

    // ---------------------------------------------------------------------------------------
    // Rule modes
    // ---------------------------------------------------------------------------------------

    @Test
    void blankRuleIsLockedAndNeverGrantsAnything() {
        for (String rule : new String[] {null, "", "   "}) {
            assertThat(ruleService.resolveMode(rule), is(RuleMode.LOCKED));
            assertThat(ruleService.canAccess(rule, OWNER_FIELD, USER, ownedBy("user-1"), null), is(false));
            assertThat(ruleService.canAccess(rule, OWNER_FIELD, GUEST, ownerless(), null), is(false));
            assertThat("a locked rule has no query representation at all",
                    ruleService.listFilter(rule, OWNER_FIELD, USER), is(nullValue()));
        }
    }

    @Test
    void starRuleIsPublicForEveryone() {
        assertThat(ruleService.resolveMode("*"), is(RuleMode.PUBLIC));
        assertThat(ruleService.canAccess("*", OWNER_FIELD, GUEST, ownedBy("user-1"), null), is(true));
        assertThat(ruleService.canAccess("*", OWNER_FIELD, USER, ownerless(), null), is(true));
        assertThat(ruleService.listFilter("*", OWNER_FIELD, GUEST), is(Filters.empty()));
    }

    @Test
    void onlyTheDocumentedRuleValuesAreAccepted() {
        ruleService.validateRule("*", OWNER_FIELD);
        ruleService.validateRule("auth", OWNER_FIELD);
        ruleService.validateRule("owner", OWNER_FIELD);
        ruleService.validateRule(null, OWNER_FIELD);

        for (String rule : new String[] {
                "status = published",
                "record.owner = auth.id",
                "auth.role = 'superadmin'",
                "true",
                "1 = 1"}) {
            assertThat(rule, thrownBy(() -> ruleService.validateRule(rule, OWNER_FIELD)), is(true));
        }
    }

    private static boolean thrownBy(Runnable runnable) {
        try {
            runnable.run();
            return false;
        } catch (RuleParseException e) {
            return true;
        }
    }

    // ---------------------------------------------------------------------------------------
    // LIST and VIEW must agree, for every rule, caller and record
    // ---------------------------------------------------------------------------------------

    private static Stream<Arguments> ruleCallerRecord() {
        List<Arguments> arguments = new java.util.ArrayList<>();
        for (String rule : new String[] {"*", "auth", "owner"}) {
            for (AuthContext caller : List.of(GUEST, USER, OTHER_USER)) {
                for (Document record : List.of(ownedBy("user-1"), ownedBy("user-2"), ownerless())) {
                    arguments.add(Arguments.of(rule, caller, record));
                }
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "rule={0} caller={1} record={2}")
    @MethodSource("ruleCallerRecord")
    void listAndViewAgreeOnEveryRecord(String rule, AuthContext caller, Document record) {
        boolean viewGrants = ruleService.canAccess(rule, OWNER_FIELD, caller, record, null);

        Bson filter = ruleService.listFilter(rule, OWNER_FIELD, caller);
        boolean listGrants = matches(filter, record);

        assertThat(
                "LIST and VIEW must not disagree for rule '" + rule + "'",
                listGrants,
                is(viewGrants));
    }

    /** Mirrors the few filter shapes the converter can produce against a record. */
    private static boolean matches(Bson filter, Document record) {
        if (filter == null) {
            return false;
        }
        if (filter.equals(Filters.empty())) {
            return true;
        }
        if (filter.equals(DENIED)) {
            return false;
        }
        // The only other shape in use is an equality on the owner field
        Document rendered = filter.toBsonDocument(Document.class,
                com.mongodb.MongoClientSettings.getDefaultCodecRegistry()).toString().isEmpty()
                ? new Document()
                : Document.parse(filter.toBsonDocument(Document.class,
                        com.mongodb.MongoClientSettings.getDefaultCodecRegistry()).toJson());

        for (Map.Entry<String, Object> entry : rendered.entrySet()) {
            if (!java.util.Objects.equals(record.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------
    // A caller without a proven identity never matches
    // ---------------------------------------------------------------------------------------

    @Test
    void guestNeverMatchesAnAuthOrOwnerRule() {
        for (String rule : new String[] {"auth", "owner"}) {
            assertThat(ruleService.canAccess(rule, OWNER_FIELD, GUEST, ownedBy("user-1"), null), is(false));
            assertThat("an ownerless record must not match a guest either",
                    ruleService.canAccess(rule, OWNER_FIELD, GUEST, ownerless(), null), is(false));
            assertThat(ruleService.canAccess(rule, OWNER_FIELD, GUEST, ownerless(), Map.of()), is(false));
            assertThat(ruleService.listFilter(rule, OWNER_FIELD, GUEST), is(DENIED));
        }
    }

    @Test
    void authRuleGrantsEveryRecordToAnAuthenticatedCaller() {
        assertThat(ruleService.canAccess("auth", OWNER_FIELD, USER, ownedBy("user-2"), null), is(true));
        assertThat(ruleService.canAccess("auth", OWNER_FIELD, USER, ownerless(), null), is(true));
        assertThat(ruleService.listFilter("auth", OWNER_FIELD, USER), is(Filters.empty()));
    }

    @Test
    void ownerRuleGrantsOnlyTheCallersOwnRecords() {
        assertThat(ruleService.canAccess("owner", OWNER_FIELD, USER, ownedBy("user-1"), null), is(true));
        assertThat(ruleService.canAccess("owner", OWNER_FIELD, USER, ownedBy("user-2"), null), is(false));
        assertThat(ruleService.canAccess("owner", OWNER_FIELD, USER, ownerless(), null), is(false));
        assertThat(ruleService.listFilter("owner", OWNER_FIELD, USER), is(Filters.eq(OWNER_FIELD, "user-1")));
    }

    @Test
    void ownerRuleHonoursACustomOwnerField() {
        String field = "createdBy";
        Document record = new Document("id", "record-1").append(field, "user-1");

        assertThat(ruleService.normalizeRule("owner", field), is("record." + field + " = auth.id"));
        assertThat(ruleService.canAccess("owner", field, USER, record, null), is(true));
        assertThat(ruleService.canAccess("owner", field, OTHER_USER, record, null), is(false));
        assertThat(ruleService.listFilter("owner", field, USER), is(Filters.eq(field, "user-1")));
    }

    // ---------------------------------------------------------------------------------------
    // Create: the record does not exist yet, so the body stands in for it
    // ---------------------------------------------------------------------------------------

    @Test
    void createUnderOwnerRuleAssignsTheCallerAsOwner() {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "mine");

        assertThat(ruleService.canAccess("owner", OWNER_FIELD, USER, null, body), is(true));
        assertThat(OwnerFieldUtils.effectiveCreateBody("owner", OWNER_FIELD, USER, body).get(OWNER_FIELD),
                is("user-1"));
    }

    @Test
    void createUnderOwnerRuleRejectsASpoofedOwner() {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "not mine");
        body.put(OWNER_FIELD, "user-2");

        assertThat(ruleService.canAccess("owner", OWNER_FIELD, USER, null, body), is(false));
        assertThat("a spoofed owner leaves no usable body behind",
                OwnerFieldUtils.effectiveCreateBody("owner", OWNER_FIELD, USER, body), is(anEmptyMap()));
    }

    @Test
    void createUnderOwnerRuleAcceptsTheCallersOwnIdAsOwner() {
        Map<String, Object> body = new HashMap<>();
        body.put(OWNER_FIELD, "user-1");

        assertThat(ruleService.canAccess("owner", OWNER_FIELD, USER, null, body), is(true));
    }

    @Test
    void createUnderOwnerRuleDeniesAGuest() {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "anonymous");

        assertThat(ruleService.canAccess("owner", OWNER_FIELD, GUEST, null, body), is(false));
        assertThat(ruleService.canAccess("owner", OWNER_FIELD, GUEST, null, Map.of()), is(false));
        assertThat(ruleService.canAccess("owner", OWNER_FIELD, GUEST, null, null), is(false));
    }

    @Test
    void createUnderAuthRuleDependsOnlyOnTheCaller() {
        assertThat(ruleService.canAccess("auth", OWNER_FIELD, USER, null, Map.of()), is(true));
        assertThat(ruleService.canAccess("auth", OWNER_FIELD, GUEST, null, Map.of()), is(false));
    }

    // ---------------------------------------------------------------------------------------
    // Rule selection per operation
    // ---------------------------------------------------------------------------------------

    @Test
    void eachOperationUsesItsOwnRule() {
        models.CollectionRules rules =
                new models.CollectionRules("*", "auth", "owner", null, "*", OWNER_FIELD);

        assertThat(ruleService.ruleFor(rules, RuleOperation.LIST), is("*"));
        assertThat(ruleService.ruleFor(rules, RuleOperation.VIEW), is("auth"));
        assertThat(ruleService.ruleFor(rules, RuleOperation.CREATE), is("owner"));
        assertThat(ruleService.ruleFor(rules, RuleOperation.UPDATE), is(nullValue()));
        assertThat(ruleService.ruleFor(rules, RuleOperation.DELETE), is("*"));
        assertThat(ruleService.ruleFor(null, RuleOperation.LIST), is(nullValue()));
    }

    @Test
    void ownerFieldFallsBackToTheDefaultName() {
        assertThat(models.CollectionRules.locked().ownerFieldOrDefault(), is(OWNER_FIELD));
        assertThat(new models.CollectionRules(null, null, null, null, null, "  ").ownerFieldOrDefault(),
                is(OWNER_FIELD));
        assertThat(new models.CollectionRules(null, null, null, null, null, "createdBy").ownerFieldOrDefault(),
                is("createdBy"));
    }
}
