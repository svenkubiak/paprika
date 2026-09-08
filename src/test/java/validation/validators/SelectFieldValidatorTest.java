package validation.validators;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import enums.FieldType;
import io.mangoo.utils.JsonUtils;
import models.FieldDefinition;
import models.FieldOptions;
import org.junit.jupiter.api.Test;
import validation.ValidationContext;
import validation.ValidationResult;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class SelectFieldValidatorTest {
    private final SelectFieldValidator validator = new SelectFieldValidator();

    @Test
    void acceptsAllowedSingleValue() {
        FieldDefinition field = selectField(List.of("draft", "published"), 1);
        ValidationResult result = new ValidationResult();

        validator.validate(field, JsonUtils.getMapper().getNodeFactory().textNode("draft"), result, ValidationContext.empty());

        assertThat(result.isValid(), is(true));
    }

    @Test
    void rejectsUnknownSingleValue() {
        FieldDefinition field = selectField(List.of("draft", "published"), 1);
        ValidationResult result = new ValidationResult();

        validator.validate(field, JsonUtils.getMapper().getNodeFactory().textNode("archived"), result, ValidationContext.empty());

        assertThat(result.isValid(), is(false));
        assertThat(result.errors().getFirst().message(), is("Value is not an allowed option"));
    }

    @Test
    void acceptsAllowedMultipleValues() {
        FieldDefinition field = selectField(List.of("a", "b", "c"), 2);
        ArrayNode value = JsonUtils.getMapper().createArrayNode().add("a").add("b");
        ValidationResult result = new ValidationResult();

        validator.validate(field, value, result, ValidationContext.empty());

        assertThat(result.isValid(), is(true));
    }

    @Test
    void rejectsTooManyMultipleValues() {
        FieldDefinition field = selectField(List.of("a", "b", "c"), 2);
        ArrayNode value = JsonUtils.getMapper().createArrayNode().add("a").add("b").add("c");
        ValidationResult result = new ValidationResult();

        validator.validate(field, value, result, ValidationContext.empty());

        assertThat(result.isValid(), is(false));
        assertThat(result.errors().getFirst().message(), is("Too many selected values"));
    }

    @Test
    void rejectsDuplicateMultipleValues() {
        FieldDefinition field = selectField(List.of("a", "b"), 2);
        ArrayNode value = JsonUtils.getMapper().createArrayNode().add("a").add("a");
        ValidationResult result = new ValidationResult();

        validator.validate(field, value, result, ValidationContext.empty());

        assertThat(result.isValid(), is(false));
        assertThat(result.errors().getFirst().message(), is("Duplicate selected value"));
    }

    private static FieldDefinition selectField(List<String> values, int maxSelect) {
        return new FieldDefinition(
                "status",
                FieldType.SELECT,
                true,
                false,
                FieldOptions.forSelect(values, maxSelect));
    }
}
