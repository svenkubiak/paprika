package rules;

import constants.SystemCollections;
import enums.FieldType;
import models.CollectionDefinition;
import models.CollectionRules;
import models.FieldDefinition;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ListSortParserTest {

    private static final CollectionDefinition POSTS = new CollectionDefinition(
            "posts-id",
            "posts",
            List.of(
                    new FieldDefinition("title", FieldType.STRING, true, false, null),
                    new FieldDefinition("price", FieldType.NUMBER, false, true, null),
                    new FieldDefinition("status", FieldType.SELECT, false, true, null),
                    new FieldDefinition("cover", FieldType.FILE, false, true, null),
                    new FieldDefinition("payload", FieldType.JSON, false, true, null)),
            List.of(),
            new CollectionRules("*", "*", "*", "*", "*", null),
            false);

    private static final CollectionDefinition USERS = new CollectionDefinition(
            "users-id",
            SystemCollections.USERS,
            List.of(
                    new FieldDefinition("username", FieldType.STRING, true, false, null),
                    new FieldDefinition("email", FieldType.EMAIL, false, true, null),
                    new FieldDefinition("role", FieldType.STRING, false, true, null),
                    new FieldDefinition("password", FieldType.STRING, true, false, null),
                    new FieldDefinition("nickname", FieldType.STRING, false, true, null)),
            List.of(),
            CollectionRules.locked(),
            true);

    @Test
    void singleFieldFormStaysValid() {
        assertThat(sort("title:asc", POSTS), is(BsonDocument.parse("{\"title\": 1}")));
        assertThat(sort("createdAt:desc", POSTS), is(BsonDocument.parse("{\"createdAt\": -1}")));
    }

    @Test
    void keepsTheOrderOfSeveralFields() {
        assertThat(sort("status:asc,price:desc,title:asc", POSTS),
                is(BsonDocument.parse("{\"status\": 1, \"price\": -1, \"title\": 1}")));
        assertThat(sort("price:desc,id:asc", POSTS), is(BsonDocument.parse("{\"price\": -1, \"id\": 1}")));
    }

    @Test
    void blankSortMeansNoSort() {
        assertThat(ListSortParser.parse(null, POSTS), is(nullValue()));
        assertThat(ListSortParser.parse(" ", POSTS), is(nullValue()));
    }

    @Test
    void reportsEveryProblemWithTheSort() {
        assertThat(error("title", POSTS), is("sort 'title': must be of the form <field>:asc|desc"));
        assertThat(error(":asc", POSTS), is("sort ':asc': field must not be empty"));
        assertThat(error("nope:asc", POSTS), is("sort 'nope:asc': unknown field 'nope'"));
        assertThat(error("title:sideways", POSTS), is("sort 'title:sideways': unknown sort direction 'sideways'"));
        assertThat(error("title:ASC", POSTS), is("sort 'title:ASC': unknown sort direction 'ASC'"));
        assertThat(error("payload:asc", POSTS), is("sort 'payload:asc': json field 'payload' is not sortable"));
        assertThat(error("cover:desc", POSTS), is("sort 'cover:desc': file field 'cover' is not sortable"));
        assertThat(error("title:asc,", POSTS), is("sort 'title:asc,': must be of the form <field>:asc|desc"));
        assertThat(error("title:asc,title:desc", POSTS), is("sort 'title:asc,title:desc': field 'title' appears more than once"));
        assertThat(error("title:asc,nope:desc", POSTS), is("sort 'title:asc,nope:desc': unknown field 'nope'"));
    }

    @Test
    void fieldNamesThatLookLikeQuerySyntaxAreUnknownFields() {
        for (String field : List.of("$natural", "title.length", "_id", "$where")) {
            assertThat(field, error(field + ":asc", POSTS), is("sort '" + field + ":asc': unknown field '" + field + "'"));
        }
    }

    @Test
    void acceptsUpToThreeFieldsAndRejectsMore() {
        assertThat(sort("title:asc,price:asc,status:asc", POSTS).size(), is(ListSortParser.MAX_FIELDS));
        assertThat(error("title:asc,price:asc,status:asc,id:asc", POSTS),
                is("sort 'title:asc,price:asc,status:asc,id:asc': at most 3 sort fields are allowed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "passwordHash", "passwordSalt", "resetTokenHash", "resetTokenExpiresAt",
            "verifyTokenHash", "verifyTokenExpiresAt"})
    void rejectsNeverReturnedUserFieldsAsFirstAndSecondaryKey(String field) {
        assertThat(error(field + ":asc", USERS), is("sort '" + field + ":asc': field '" + field
                + "' is never returned and cannot be used"));
        assertThat(error("username:asc," + field + ":desc", USERS), is("sort 'username:asc," + field
                + ":desc': field '" + field + "' is never returned and cannot be used"));
    }

    @Test
    void otherUserFieldsStaySortable() {
        assertThat(sort("username:asc,email:desc,role:asc", USERS),
                is(BsonDocument.parse("{\"username\": 1, \"email\": -1, \"role\": 1}")));
        assertThat(sort("nickname:desc,createdAt:asc", USERS),
                is(BsonDocument.parse("{\"nickname\": -1, \"createdAt\": 1}")));
    }

    private static BsonDocument sort(String raw, CollectionDefinition definition) {
        return ListSortParser.parse(raw, definition).toBsonDocument();
    }

    private static String error(String raw, CollectionDefinition definition) {
        return assertThrows(ListSortParser.InvalidSortException.class,
                () -> ListSortParser.parse(raw, definition)).getMessage();
    }
}
