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
}
