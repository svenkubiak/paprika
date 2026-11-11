package validation.validators;

import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.utils.JsonUtils;
import models.FieldDefinition;
import models.FieldOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.JsonNode;
import utils.TenantTestUtils;
import validation.ValidationContext;
import validation.ValidationResult;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * A relation id is only stored when the target record exists in this tenant. Several ids are
 * checked as a set: the wrong shape, a duplicate and a missing record each stop the list.
 */
@ExtendWith({TestRunner.class})
class RelationFieldValidatorTest {

    @Test
    void severalIdsMustBeUniqueStringsThatExist() {
        String authors = "authors_" + Long.toHexString(System.nanoTime());
        TenantTestUtils.seedCollection(authors, null, List.of(
                new FieldDefinition("name", FieldType.STRING, false, true, null)));
        String ada = TenantTestUtils.seedRecord(authors, "Ada");
        RelationFieldValidator validator = Application.getInstance(RelationFieldValidator.class);
        FieldDefinition field = relation(authors, 2);
        ValidationContext context = ValidationContext.of(TenantTestUtils.defaultTenantContext());

        assertThat(validate(validator, field, node("\"" + ada + "\""), context).isValid(), is(false));
        assertThat(message(validator, field, node("\"" + ada + "\""), context),
                is("Expected array of relation ids"));
        assertThat(message(validator, field, node("[\"" + ada + "\",\"" + ada + "\",\"x\"]"), context),
                is("Too many relation ids"));
        assertThat(message(validator, field, node("[\"" + ada + "\",\"" + ada + "\"]"), context),
                is("Duplicate relation id"));
        assertThat(message(validator, field, node("[\"" + ada + "\",1]"), context),
                is("Expected array of relation ids"));
        assertThat(message(validator, field, node("[\"" + ada + "\",\"missing\"]"), context),
                containsString("Related record not found"));
        assertThat(message(validator, field, node("[\" \"]"), context),
                is("Expected relation id string"));
        assertThat(validate(validator, field, node("[\"" + ada + "\"]"), context).isValid(), is(true));
    }

    @Test
    void aRelationWithoutATargetOrATenantCannotBeChecked() {
        RelationFieldValidator validator = Application.getInstance(RelationFieldValidator.class);
        ValidationContext context = ValidationContext.of(TenantTestUtils.defaultTenantContext());

        FieldDefinition unconfigured = new FieldDefinition("author", FieldType.RELATION, false, true, null);
        assertThat(message(validator, unconfigured, node("\"id-1\""), context),
                is("Relation target collection is not configured"));

        FieldDefinition blankTarget = relation("  ", 1);
        assertThat(message(validator, blankTarget, node("\"id-1\""), context),
                is("Relation target collection is not configured"));

        FieldDefinition authors = relation("no_such_authors", 1);
        assertThat(message(validator, authors, node("\"id-1\""), ValidationContext.empty()),
                is("Relation validation requires tenant context"));
        assertThat(message(validator, authors, node("\"id-1\""), context),
                containsString("Related collection not found"));
        assertThat(message(validator, authors, node("12"), context),
                is("Expected relation id string"));
    }

    private static FieldDefinition relation(String collection, int maxSelect) {
        return new FieldDefinition(
                "author", FieldType.RELATION, false, true, FieldOptions.forRelation(collection, maxSelect, false));
    }

    private static String message(
            RelationFieldValidator validator, FieldDefinition field, JsonNode value, ValidationContext context) {
        ValidationResult result = validate(validator, field, value, context);
        assertThat(result.isValid(), is(false));
        return result.errors().getFirst().message();
    }

    private static ValidationResult validate(
            RelationFieldValidator validator, FieldDefinition field, JsonNode value, ValidationContext context) {
        ValidationResult result = new ValidationResult();
        validator.validate(field, value, result, context);
        return result;
    }

    private static JsonNode node(String json) {
        return JsonUtils.getMapper().readTree(json);
    }
}
