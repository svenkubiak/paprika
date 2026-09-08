package utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mangoo.utils.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

class DbUtilsTest {

    @Test
    void convertsJsonObjectToDocument() throws Exception {
        Object value = DbUtils.toMongoValue(JsonUtils.getMapper().readTree("{\"theme\":\"dark\"}"));

        assertThat(value, instanceOf(org.bson.Document.class));
        assertThat(((org.bson.Document) value).getString("theme"), is("dark"));
    }

    @Test
    void convertsJsonArrayRecursively() throws Exception {
        Object value = DbUtils.toMongoValue(JsonUtils.getMapper().readTree("[1,{\"a\":2}]"));

        assertThat(value, instanceOf(List.class));
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) value;
        assertThat(list.get(0), is(1));
        assertThat(list.get(1), instanceOf(org.bson.Document.class));
        assertThat(((org.bson.Document) list.get(1)).getInteger("a"), is(2));
    }
}
