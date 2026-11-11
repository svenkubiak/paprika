package rules;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;

/** A membership field is one id or a list of them. Blanks and nulls are not memberships. */
class MembershipResolverValuesTest {

    @Test
    void valuesOfKeepsIdsAndDropsBlanks() {
        assertThat(MembershipResolver.valuesOf(null), empty());
        assertThat(MembershipResolver.valuesOf("  "), empty());
        assertThat(MembershipResolver.valuesOf("group-1"), contains("group-1"));
        assertThat(MembershipResolver.valuesOf(12), contains("12"));
        assertThat(MembershipResolver.valuesOf(Arrays.asList("a", null, " ", "b")), contains("a", "b"));
        assertThat(MembershipResolver.valuesOf(List.of()), empty());
    }
}
