package validation.validators;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import enums.FieldType;
import io.mangoo.utils.JsonUtils;
import models.FieldDefinition;
import org.junit.jupiter.api.Test;
import validation.ValidationContext;
import validation.ValidationResult;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class JsonFieldValidatorTest {
    private final JsonFieldValidator validator = new JsonFieldValidator();

    @Test
    void acceptsObjectValue() {
        FieldDefinition field = new FieldDefinition("metadata", FieldType.JSON, false, true, null);
        ObjectNode value = JsonUtils.getMapper().createObjectNode().put("theme", "dark");
        ValidationResult result = new ValidationResult();

        validator.validate(field, value, result, ValidationContext.empty());

        assertThat(result.isValid(), is(true));
    }

    @Test
    void acceptsArrayValue() {
        FieldDefinition field = new FieldDefinition("tags", FieldType.JSON, false, true, null);
        ArrayNode value = JsonUtils.getMapper().createArrayNode().add("a").add("b");
        ValidationResult result = new ValidationResult();

        validator.validate(field, value, result, ValidationContext.empty());

        assertThat(result.isValid(), is(true));
    }

    @Test
    void rejectsStringValue() {
        FieldDefinition field = new FieldDefinition("metadata", FieldType.JSON, false, true, null);
        ValidationResult result = new ValidationResult();

        validator.validate(field, JsonUtils.getMapper().getNodeFactory().textNode("{}"), result, ValidationContext.empty());

        assertThat(result.isValid(), is(false));
        assertThat(result.errors().getFirst().message(), is("Expected JSON object or array"));
    }

    @Test
    void rejectsNumberValue() {
        FieldDefinition field = new FieldDefinition("metadata", FieldType.JSON, false, true, null);
        ValidationResult result = new ValidationResult();

        validator.validate(field, JsonUtils.getMapper().getNodeFactory().numberNode(42), result, ValidationContext.empty());

        assertThat(result.isValid(), is(false));
    }
}
