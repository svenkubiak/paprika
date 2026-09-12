package rules;

import auth.AuthContext;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.model.Filters;
import enums.Role;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import rules.ast.RuleNode;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The expression engine below the rule shorthands.
 * <p>
 * {@link RuleService#validateRule} currently only accepts {@code *}, {@code auth} and {@code owner},
 * but the parser, the evaluator and the Mongo converter implement a far larger expression language
 * underneath. That part is what a future "custom rules" feature would expose directly, and it is
 * unreachable - hence untested - through the service. These tests address it directly, so the
 * behaviour is pinned down before it is ever switched on, and so mutation testing has something to
 * measure against.
 * <p>
 * The recurring theme is the same as for the shorthands: an operand that cannot be resolved for the
 * caller must never satisfy a comparison, in neither of the two evaluation paths.
 */
class RuleExpressionEngineTest {
    private static final AuthContext USER = AuthContext.of("user-1", Role.USER, "tenant-1");
    private static final AuthContext GUEST = AuthContext.guest();

    private static boolean evaluate(String expression, AuthContext auth, Document record) {
        return evaluate(expression, auth, record, Map.of());
    }

    private static boolean evaluate(String expression, AuthContext auth, Document record, Map<String, Object> body) {
        RuleNode node = RuleParser.parse(expression);
        return RuleEvaluator.evaluate(node, RuleEvaluationContext.of(auth, record, body));
    }

    private static Bson filter(String expression, AuthContext auth) {
        return RuleToMongoConverter.toFilter(RuleParser.parse(expression), auth);
    }

    private static String json(Bson filter) {
        return filter.toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry()).toJson();
    }

    // ---------------------------------------------------------------------------------------
    // Comparison operators
    // ---------------------------------------------------------------------------------------

    @Test
    void comparesRecordFieldsAgainstLiterals() {
        Document record = new Document("status", "published").append("views", 42);

        assertThat(evaluate("record.status = 'published'", USER, record), is(true));
        assertThat(evaluate("record.status != 'draft'", USER, record), is(true));
        assertThat(evaluate("record.views > 41", USER, record), is(true));
        assertThat(evaluate("record.views >= 42", USER, record), is(true));
        assertThat(evaluate("record.views < 43", USER, record), is(true));
        assertThat(evaluate("record.views <= 42", USER, record), is(true));

        assertThat(evaluate("record.status = 'draft'", USER, record), is(false));
        assertThat(evaluate("record.views > 42", USER, record), is(false));
        assertThat(evaluate("record.views < 42", USER, record), is(false));
    }

    @Test
    void comparisonOperatorsTranslateToTheMatchingMongoOperator() {
        assertThat(json(filter("record.views > 10", USER)), containsString("$gt"));
        assertThat(json(filter("record.views >= 10", USER)), containsString("$gte"));
        assertThat(json(filter("record.views < 10", USER)), containsString("$lt"));
        assertThat(json(filter("record.views <= 10", USER)), containsString("$lte"));
        assertThat(json(filter("record.status != 'draft'", USER)), containsString("$ne"));
        assertThat(json(filter("record.status = 'published'", USER)), containsString("published"));
    }

    @Test
    void aFieldWithoutValueNeverSatisfiesAComparison() {
        Document record = new Document("id", "record-1");

        assertThat(evaluate("record.status = 'published'", USER, record), is(false));
        assertThat("comparing a missing value must fail rather than throw",
                evaluate("record.status != 'published'", USER, record), is(true));
        assertThrows(RuleParseException.class, () -> evaluate("record.views > 10", USER, record));
    }

    // ---------------------------------------------------------------------------------------
    // Boolean composition
    // ---------------------------------------------------------------------------------------

    @Test
    void combinesConditionsWithAndOrAndNot() {
        Document record = new Document("status", "published").append("owner", "user-1");

        assertThat(evaluate("record.status = 'published' and record.owner = auth.id", USER, record), is(true));
        assertThat(evaluate("record.status = 'draft' and record.owner = auth.id", USER, record), is(false));
        assertThat(evaluate("record.status = 'draft' or record.owner = auth.id", USER, record), is(true));
        assertThat(evaluate("not record.status = 'draft'", USER, record), is(true));
        assertThat(evaluate("not record.status = 'published'", USER, record), is(false));
    }

    @Test
    void booleanCompositionTranslatesToMongoOperators() {
        assertThat(json(filter("record.a = 'x' and record.b = 'y'", USER)), containsString("$and"));
        assertThat(json(filter("record.a = 'x' or record.b = 'y'", USER)), containsString("$or"));
        assertThat(json(filter("not record.a = 'x'", USER)), containsString("$not"));
    }

    @Test
    void inListMatchesAnyOfTheGivenValues() {
        Document record = new Document("status", "published");

        assertThat(evaluate("record.status in ('draft', 'published')", USER, record), is(true));
        assertThat(evaluate("record.status in ('draft', 'archived')", USER, record), is(false));
        assertThat(json(filter("record.status in ('draft', 'published')", USER)), containsString("$in"));
    }

    // ---------------------------------------------------------------------------------------
    // auth and body operands
    // ---------------------------------------------------------------------------------------

    @Test
    void authFieldsResolveFromTheCaller() {
        Document record = new Document("owner", "user-1").append("tenant", "tenant-1");

        assertThat(evaluate("record.owner = auth.id", USER, record), is(true));
        assertThat(evaluate("record.tenant = auth.tenantId", USER, record), is(true));
        assertThat(evaluate("auth.role = 'user'", USER, record), is(true));
        assertThat(evaluate("auth.role = 'superadmin'", USER, record), is(false));
    }

    @Test
    void unresolvableAuthOperandsNeverSatisfyAComparison() {
        Document ownerless = new Document("id", "record-1");

        // The guest has no id, and an ownerless record has no owner: two unknowns must not
        // compare equal, in neither evaluation path
        assertThat(evaluate("record.owner = auth.id", GUEST, ownerless), is(false));
        assertThat(evaluate("record.owner != auth.id", GUEST, ownerless), is(false));
        assertThat(evaluate("auth.id != null", GUEST, ownerless), is(false));
        assertThat(evaluate("auth.id", GUEST, ownerless), is(false));
        assertThat(evaluate("auth.id in ('user-1')", GUEST, ownerless), is(false));

        assertThat(json(filter("record.owner = auth.id", GUEST)), containsString("__paprika_denied__"));
        assertThat(json(filter("auth.id != null", GUEST)), containsString("__paprika_denied__"));
        assertThat(json(filter("auth.id", GUEST)), containsString("__paprika_denied__"));
    }

    @Test
    void authOperandsOfAnAuthenticatedCallerResolveToAConstantFilter() {
        assertThat(filter("auth.id != null", USER), is(Filters.empty()));
        assertThat(filter("auth.id", USER), is(Filters.empty()));
        assertThat(filter("auth.role = 'user'", USER), is(Filters.empty()));
        assertThat(json(filter("auth.role = 'superadmin'", USER)), containsString("__paprika_denied__"));
    }

    @Test
    void bodyFieldsResolveFromTheSubmittedPayload() {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "published");

        assertThat(evaluate("body.status = 'published'", USER, null, body), is(true));
        assertThat(evaluate("body.status = 'draft'", USER, null, body), is(false));
        assertThat("a field missing from the body must not match", 
                evaluate("body.missing = 'x'", USER, null, body), is(false));
    }

    @Test
    void literalsDecideOnTheirOwn() {
        assertThat(evaluate("true", USER, new Document()), is(true));
        assertThat(evaluate("false", USER, new Document()), is(false));
        assertThat(filter("true", USER), is(Filters.empty()));
        assertThat(json(filter("false", USER)), containsString("__paprika_denied__"));
    }

    // ---------------------------------------------------------------------------------------
    // Parser
    // ---------------------------------------------------------------------------------------

    /**
     * The same condition can be written in several shapes. All of them have to produce the same
     * decision, otherwise the phrasing of a rule - not its meaning - would decide who gets access.
     */
    @Test
    void equivalentPhrasingsOfAConditionBehaveIdentically() {
        Document mine = new Document("owner", "user-1");
        Document foreign = new Document("owner", "user-2");

        for (String expression : new String[] {
                "record.owner = auth.id",
                "owner = auth.id",            // the record prefix is optional
                "auth.id = record.owner",     // operands the other way round
                "auth.id = owner"}) {

            assertThat(expression, evaluate(expression, USER, mine), is(true));
            assertThat(expression, evaluate(expression, USER, foreign), is(false));
            assertThat(expression, filter(expression, USER), is(Filters.eq("owner", "user-1")));
            assertThat(expression + " must deny a guest",
                    json(filter(expression, GUEST)), containsString("__paprika_denied__"));
        }
    }

    @Test
    void aLiteralOnEitherSideComparesTheSame() {
        Document record = new Document("status", "published");

        assertThat(evaluate("record.status = 'published'", USER, record), is(true));
        assertThat(evaluate("'published' = record.status", USER, record), is(true));
        assertThat(json(filter("'published' = record.status", USER)), containsString("published"));
    }

    @Test
    void aFieldExistenceCheckTranslatesToAnExistsFilter() {
        assertThat(evaluate("record.owner", USER, new Document("owner", "user-1")), is(true));
        assertThat(evaluate("record.owner", USER, new Document("id", "record-1")), is(false));
        assertThat(json(filter("record.owner", USER)), containsString("$exists"));
    }

    @Test
    void rejectsMalformedExpressions() {
        for (String expression : new String[] {
                "record.status =",
                "= 'published'",
                "record.status ~ 'published'",
                "record.status in 'published'",
                "(record.status = 'published'",
                ""}) {
            assertThrows(RuleParseException.class, () -> RuleParser.parse(expression),
                    "must reject: " + expression);
        }
    }

    @Test
    void rejectsAnUnknownOperandPrefix() {
        assertThrows(RuleParseException.class,
                () -> evaluate("session.id = 'x'", USER, new Document()));
    }

    @Test
    void parenthesesGroupConditions() {
        Document record = new Document("status", "draft").append("owner", "user-1");

        assertThat(evaluate("record.status = 'published' or (record.owner = auth.id and record.status = 'draft')",
                USER, record), is(true));
        assertThat(evaluate("(record.status = 'published' or record.owner = auth.id) and record.status = 'published'",
                USER, record), is(false));
    }
}
