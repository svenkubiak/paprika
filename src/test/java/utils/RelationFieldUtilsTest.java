package utils;

import enums.FieldType;
import models.CollectionDefinition;
import models.CollectionRules;
import models.FieldDefinition;
import models.FieldOptions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/** A relation value is either one id or a list of them; blanks and a value of the wrong shape contribute nothing. */
class RelationFieldUtilsTest {

    @Test
    void oneIdAndSeveralIdsAreReadTheWayTheyAreStored() {
        FieldDefinition single = relation(1);
        assertThat(RelationFieldUtils.relationIds(null, single), empty());
        assertThat(RelationFieldUtils.relationIds("  ", single), empty());
        assertThat(RelationFieldUtils.relationIds("ada", single), contains("ada"));
        assertThat(RelationFieldUtils.relationIds(12, single), contains("12"));

        FieldDefinition several = relation(2);
        assertThat(RelationFieldUtils.relationIds(Arrays.asList("a", null, " ", "b"), several), contains("a", "b"));
        assertThat(RelationFieldUtils.relationIds("not-a-list", several), empty());
        assertThat(RelationFieldUtils.uniqueRelationIds(List.of("a", "a", "b"), several), contains("a", "b"));
    }

    @Test
    void onlyRelationFieldsAreCollected() {
        FieldDefinition title = new FieldDefinition("title", FieldType.STRING, false, true, null);
        FieldDefinition author = relation(1);
        CollectionDefinition posts = new CollectionDefinition(
                "id", "posts", List.of(title, author), List.of(), CollectionRules.locked(), false);

        assertThat(RelationFieldUtils.relationFields(posts), contains(author));
        assertThat(RelationFieldUtils.relationFields(new CollectionDefinition(
                "id", "posts", null, List.of(), CollectionRules.locked(), false)), empty());
    }

    private static FieldDefinition relation(int maxSelect) {
        return new FieldDefinition(
                "author", FieldType.RELATION, false, true, FieldOptions.forRelation("users", maxSelect, false));
    }
}
