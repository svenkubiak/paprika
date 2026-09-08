package validation.validators;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

class FileFieldValidatorTest {
    private final FileFieldValidator validator = new FileFieldValidator();

    @Test
    void rejectsJsonFileValues() {
        FieldDefinition field = new FieldDefinition(
                "avatar",
                FieldType.FILE,
                false,
                true,
                FieldOptions.forFile(1024, List.of("image/*"), 1));
        ObjectNode value = JsonUtils.getMapper().createObjectNode().put("id", "file-1");
        ValidationResult result = new ValidationResult();

        validator.validate(field, value, result, ValidationContext.empty());

        assertThat(result.isValid(), is(false));
        assertThat(result.errors().getFirst().message(), is("File fields must be uploaded as multipart/form-data"));
    }
}
