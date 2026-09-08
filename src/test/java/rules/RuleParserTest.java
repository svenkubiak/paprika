package rules;

import org.junit.jupiter.api.Test;
import rules.ast.RuleNode;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

class RuleParserTest {

    @Test
    void parsesComparisonAndOr() {
        RuleNode node = RuleParser.parse("status = published or auth.id = record.owner");

        assertThat(node, instanceOf(RuleNode.Binary.class));
        RuleNode.Binary binary = (RuleNode.Binary) node;
        assertThat(binary.operator(), is("or"));
    }

    @Test
    void parsesInList() {
        RuleNode node = RuleParser.parse("auth.role in (editor, admin)");

        assertThat(node, instanceOf(RuleNode.InList.class));
        RuleNode.InList inList = (RuleNode.InList) node;
        assertThat(inList.values().size(), is(2));
    }

    @Test
    void rejectsInvalidExpression() {
        org.junit.jupiter.api.Assertions.assertThrows(
                RuleParseException.class,
                () -> RuleParser.parse("status ==")
        );
    }
}
