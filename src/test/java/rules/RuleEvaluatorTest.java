package rules;

import auth.AuthContext;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import rules.ast.RuleNode;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class RuleEvaluatorTest {

    private final RuleService ruleService = new RuleService();

    @Test
    void evaluatesAuthShorthand() {
        RuleNode node = RuleParser.parse(ruleService.normalizeRule("auth", "owner"));
        AuthContext guest = AuthContext.guest();
        AuthContext user = AuthContext.user("u1");

        assertThat(RuleEvaluator.evaluate(node, RuleEvaluationContext.of(guest)), is(false));
        assertThat(RuleEvaluator.evaluate(node, RuleEvaluationContext.of(user)), is(true));
    }

    @Test
    void evaluatesOwnerRule() {
        RuleNode node = RuleParser.parse(ruleService.normalizeRule("owner", "owner"));
        Document record = new Document("owner", "u1");
        AuthContext owner = AuthContext.user("u1");
        AuthContext other = AuthContext.user("u2");

        assertThat(RuleEvaluator.evaluate(node, RuleEvaluationContext.of(owner, record)), is(true));
        assertThat(RuleEvaluator.evaluate(node, RuleEvaluationContext.of(other, record)), is(false));
    }

    @Test
    void evaluatesPublicRule() {
        assertThat(ruleService.resolveMode("*"), is(RuleMode.PUBLIC));
        assertThat(ruleService.canAccess("*", "owner", AuthContext.guest(), null, null), is(true));
    }

    @Test
    void ownerRuleDeniesGuestOnRecordWithoutOwner() {
        RuleNode node = RuleParser.parse(ruleService.normalizeRule("owner", "owner"));
        Document ownerless = new Document("title", "created via admin ui");

        // Both sides resolve to "no value"; treating that as a match would hand every ownerless
        // record to anonymous callers.
        assertThat(RuleEvaluator.evaluate(node, RuleEvaluationContext.of(AuthContext.guest(), ownerless)), is(false));
        assertThat(RuleEvaluator.evaluate(node, RuleEvaluationContext.of(AuthContext.guest(), null)), is(false));
    }

    @Test
    void ownerRuleDeniesGuestThroughCanAccess() {
        Document ownerless = new Document("title", "created via admin ui");
        Document owned = new Document("owner", "u1");

        assertThat(ruleService.canAccess("owner", "owner", AuthContext.guest(), ownerless, null), is(false));
        assertThat(ruleService.canAccess("owner", "owner", AuthContext.guest(), owned, null), is(false));
        assertThat(ruleService.canAccess("owner", "owner", AuthContext.user("u1"), owned, null), is(true));
    }

    @Test
    void ownerlessRecordStaysInaccessibleToAuthenticatedUsers() {
        Document ownerless = new Document("title", "created via admin ui");

        assertThat(ruleService.canAccess("owner", "owner", AuthContext.user("u1"), ownerless, null), is(false));
    }

    @Test
    void expressionRulesWithNullBodyDoNotThrow() {
        // VIEW and DELETE pass a null body, as does realtime delivery.
        assertThat(ruleService.canAccess("auth", "owner", AuthContext.guest(), null, null), is(false));
        assertThat(ruleService.canAccess("auth", "owner", AuthContext.user("u1"), null, null), is(true));
        assertThat(ruleService.canAccess("owner", "owner", AuthContext.guest(), null, null), is(false));
    }
}
