package constants;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class SystemFieldsTest {

    @Test
    void rejectsReservedSchemaNames() {
        assertThat(SystemFields.isReservedSchemaName("id"), is(true));
        assertThat(SystemFields.isReservedSchemaName("createdAt"), is(true));
        assertThat(SystemFields.isReservedSchemaName("updatedAt"), is(true));
        assertThat(SystemFields.isReservedSchemaName("created"), is(true));
        assertThat(SystemFields.isReservedSchemaName("updated"), is(true));
        assertThat(SystemFields.isReservedSchemaName("title"), is(false));
    }
}
