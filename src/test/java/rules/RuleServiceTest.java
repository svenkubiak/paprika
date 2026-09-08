package rules;

import models.CollectionRules;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuleServiceTest {

    private final RuleService ruleService = new RuleService();

    @Test
    void lockedByDefault() {
        assertThat(ruleService.resolveMode(null), is(RuleMode.LOCKED));
    }

    @Test
    void validatesRulesOnCollection() {
        CollectionRules rules = new CollectionRules("*", "*", "*", "*", "*", "owner");
        ruleService.validateRules(rules);
    }

    @Test
    void rejectsCustomRuleSyntax() {
        assertThrows(RuleParseException.class, () -> ruleService.validateRule("status = published", "owner"));
    }

    @Test
    void normalizesOwnerShorthand() {
        assertThat(
                ruleService.normalizeRule("owner", "createdBy"),
                is("record.createdBy = auth.id")
        );
    }

    @Test
    void nullRuleForLockedCollection() {
        CollectionRules rules = CollectionRules.locked();
        assertThat(ruleService.ruleFor(rules, RuleOperation.LIST), is(nullValue()));
    }
}
