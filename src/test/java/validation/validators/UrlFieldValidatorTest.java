package validation.validators;

import enums.FieldType;
import io.mangoo.utils.JsonUtils;
import models.FieldDefinition;
import models.FieldOptions;
import org.junit.jupiter.api.Test;
import validation.ValidationContext;
import validation.ValidationResult;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class UrlFieldValidatorTest {
    private final UrlFieldValidator validator = new UrlFieldValidator();

    @Test
    void rejectsAValueThatIsNotAUrl() {
        assertThat(message("12", null), is("Expected URL"));
        assertThat(message("\"example.com\"", null), is("Invalid URL"));
        assertThat(message("\"http://[\"", null), is("Invalid URL"));
    }

    @Test
    void acceptsAnAbsoluteUrlAndStopsAtTheLengthBeforeThePattern() {
        assertThat(validate("\"https://example.com/path\"", null).isValid(), is(true));

        ValidationResult tooLong = validate(
                "\"http://example.com\"", FieldOptions.forString(null, 4, "^https://.+$"));
        assertThat(tooLong.errors().size(), is(1));
        assertThat(tooLong.errors().getFirst().message(), is("Value exceeds maxLength"));

        assertThat(message("\"http://example.com\"", FieldOptions.forString(null, 100, "^https://.+$")),
                is("Value does not match pattern"));
    }

    private String message(String json, FieldOptions options) {
        ValidationResult result = validate(json, options);
        assertThat(result.isValid(), is(false));
        return result.errors().getFirst().message();
    }

    private ValidationResult validate(String json, FieldOptions options) {
        FieldDefinition field = new FieldDefinition("website", FieldType.URL, false, true, options);
        ValidationResult result = new ValidationResult();
        validator.validate(field, JsonUtils.getMapper().readTree(json), result, ValidationContext.empty());
        return result;
    }
}
