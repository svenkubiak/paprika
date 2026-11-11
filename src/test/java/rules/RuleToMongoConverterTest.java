package rules;

import auth.AuthContext;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * List rules from several groups are OR-ed. A null entry is a group without a filter and must not
 * become a clause, and a single remaining filter must not be wrapped in an empty $or.
 */
class RuleToMongoConverterTest {

    @Test
    void combineOrDropsNullsAndCollapsesASingleFilter() {
        Bson status = Filters.eq("status", "published");
        Bson title = Filters.eq("title", "hello");

        assertThat(render(RuleToMongoConverter.combineOr(List.of())), is("{}"));
        assertThat(render(RuleToMongoConverter.combineOr(Arrays.asList(null, null))), is("{}"));
        assertThat(render(RuleToMongoConverter.combineOr(Arrays.asList(status, null))), is(render(status)));

        String either = render(RuleToMongoConverter.combineOr(List.of(status, title)));
        assertThat(either, containsString("$or"));
        assertThat(either, containsString("published"));
        assertThat(either, containsString("hello"));
    }

    @Test
    void anAuthFieldOnEitherSideBecomesTheCallerValue() {
        AuthContext caller = AuthContext.of("user-1", "editor", "tenant-1");

        assertThat(render(RuleToMongoConverter.toFilter(
                RuleParser.parse("auth.role = record.status"), caller)), containsString("editor"));
        assertThat(render(RuleToMongoConverter.toFilter(
                RuleParser.parse("record.status = auth.role"), caller)), containsString("editor"));
        assertThat(render(RuleToMongoConverter.toFilter(
                RuleParser.parse("status = null"), AuthContext.guest())), containsString("__paprika_denied__"));
    }

    private static String render(Bson filter) {
        return filter.toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry()).toJson();
    }
}
