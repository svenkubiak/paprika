package rules;

import auth.AuthContext;
import com.mongodb.client.model.Filters;
import enums.Role;
import models.CollectionRules;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
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

    /**
     * The "auth" rule normalizes to "auth.id != null", a comparison without any record field.
     * It must not be mistaken for an unsatisfiable condition, or a list would come back empty for
     * exactly the callers the rule grants access to, while VIEW on the same records succeeds.
     */
    @Test
    void authListRuleMatchesEveryRecordForAuthenticatedCaller() {
        AuthContext user = AuthContext.of("user-1", Role.USER, "tenant-1");

        assertThat(ruleService.listFilter("auth", "owner", user), is(Filters.empty()));
        assertThat(
                "a rule granted for LIST must be granted for VIEW as well",
                ruleService.canAccess("auth", "owner", user, new org.bson.Document("id", "record-1"), null),
                is(true));
    }

    @Test
    void authListRuleMatchesNoRecordForGuest() {
        AuthContext guest = AuthContext.guest();

        assertThat(ruleService.listFilter("auth", "owner", guest), is(not(Filters.empty())));
        assertThat(
                ruleService.canAccess("auth", "owner", guest, new org.bson.Document("id", "record-1"), null),
                is(false));
    }

    @Test
    void ownerListRuleScopesToTheCallerAndDeniesGuests() {
        AuthContext user = AuthContext.of("user-1", Role.USER, "tenant-1");

        assertThat(ruleService.listFilter("owner", "owner", user), is(Filters.eq("owner", "user-1")));
        assertThat(ruleService.listFilter("owner", "owner", AuthContext.guest()), is(not(Filters.empty())));
    }

    @Test
    void membershipRulesAreAcceptedValues() {
        ruleService.validateRule("group", "owner");
        ruleService.validateRule("peers", "owner");
    }

    @Test
    void theRejectionMessageListsTheMembershipRules() {
        RuleParseException thrown = assertThrows(
                RuleParseException.class,
                () -> ruleService.validateRule("teams", "owner"));

        assertThat(thrown.getMessage().contains("group"), is(true));
        assertThat(thrown.getMessage().contains("peers"), is(true));
    }

    @Test
    void aGroupRuleWithoutConfigurationIsRejected() {
        CollectionRules rules = new CollectionRules("group", null, null, null, null, "owner");

        assertThrows(RuleParseException.class, () -> ruleService.validateRules(rules));
    }

    @Test
    void aGroupRuleWithoutTheRecordFieldIsRejected() {
        CollectionRules rules = new CollectionRules(
                "group", null, null, null, null, "owner", "memberships", "user", "crew", null);

        assertThrows(RuleParseException.class, () -> ruleService.validateRules(rules));
    }

    @Test
    void peersDoesNotNeedTheRecordField() {
        CollectionRules rules = new CollectionRules(
                "peers", null, null, null, null, "owner", "memberships", "user", "crew", null);

        ruleService.validateRules(rules);
    }

    /**
     * Without a tenant context the membership cannot be looked up. Both entry points have to say
     * "no" then - a null list filter is refused by the caller, and canAccess denies outright.
     * Never an empty filter, which would return the whole collection.
     */
    @Test
    void membershipRulesDenyWhenTheLookupCannotRun() {
        AuthContext user = AuthContext.of("user-1", Role.USER, "tenant-1");

        assertThat(ruleService.listFilter("group", "owner", user), is(nullValue()));
        assertThat(ruleService.listFilter("peers", "owner", user), is(nullValue()));
        assertThat(
                ruleService.canAccess("group", "owner", user, new org.bson.Document("id", "record-1"), null),
                is(false));
        assertThat(
                ruleService.canAccess("peers", "owner", user, new org.bson.Document("id", "user-1"), null),
                is(false));
    }
}
