package rules;

import constants.SystemCollections;
import enums.FieldType;
import models.CollectionDefinition;
import models.CollectionRules;
import models.FieldDefinition;
import models.FieldOptions;
import org.bson.BsonDocument;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ListFilterParserTest {

    private static final CollectionDefinition POSTS = new CollectionDefinition(
            "posts-id",
            "posts",
            List.of(
                    new FieldDefinition("title", FieldType.STRING, true, false, null),
                    new FieldDefinition("contact", FieldType.EMAIL, false, true, null),
                    new FieldDefinition("link", FieldType.URL, false, true, null),
                    new FieldDefinition("price", FieldType.NUMBER, false, true, null),
                    new FieldDefinition("featured", FieldType.BOOLEAN, false, true, null),
                    new FieldDefinition("publishedOn", FieldType.DATE, false, true, null),
                    new FieldDefinition("opensAt", FieldType.TIME, false, true, null),
                    new FieldDefinition("publishedAt", FieldType.DATETIME, false, true, null),
                    new FieldDefinition("status", FieldType.SELECT, false, true,
                            new FieldOptions(null, null, null, 1, List.of("draft", "review", "published"))),
                    new FieldDefinition("tags", FieldType.SELECT, false, true,
                            new FieldOptions(null, null, null, 3, List.of("a", "b", "c"))),
                    new FieldDefinition("author", FieldType.RELATION, false, true, FieldOptions.forRelation("users")),
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
                    new FieldDefinition("nickname", FieldType.STRING, false, true, null),
                    new FieldDefinition("joinedAt", FieldType.DATETIME, false, true, null)),
            List.of(),
            CollectionRules.locked(),
            true);

    @Test
    void parsesEveryOperatorInTheLegacyForm() {
        assertThat(legacy("title:eq:hello"), is(condition("title", FilterOperator.EQ, "hello")));
        assertThat(legacy("title:ne:hello"), is(condition("title", FilterOperator.NE, "hello")));
        assertThat(legacy("price:gt:10"), is(condition("price", FilterOperator.GT, 10L)));
        assertThat(legacy("price:gte:10.5"), is(condition("price", FilterOperator.GTE, 10.5)));
        assertThat(legacy("price:lt:-1"), is(condition("price", FilterOperator.LT, -1L)));
        assertThat(legacy("price:lte:1e3"), is(condition("price", FilterOperator.LTE, 1000.0)));
        assertThat(legacy("price:in:[10,20]"), is(condition("price", FilterOperator.IN, List.of(10L, 20L))));
        assertThat(legacy("title:nin:[\"a\",\"b\"]"), is(condition("title", FilterOperator.NIN, List.of("a", "b"))));
        assertThat(legacy("title:contains:ell"), is(condition("title", FilterOperator.CONTAINS, "ell")));
        assertThat(legacy("title:startsWith:he"), is(condition("title", FilterOperator.STARTS_WITH, "he")));
        assertThat(legacy("cover:null"), is(condition("cover", FilterOperator.NULL, null)));
        assertThat(legacy("cover:notnull"), is(condition("cover", FilterOperator.NOT_NULL, null)));
    }

    @Test
    void parsesEveryOperatorInTheBracketForm() {
        assertThat(bracket("title", "eq", "hello"), is(condition("title", FilterOperator.EQ, "hello")));
        assertThat(bracket("title", "ne", "hello"), is(condition("title", FilterOperator.NE, "hello")));
        assertThat(bracket("price", "gt", "10"), is(condition("price", FilterOperator.GT, 10L)));
        assertThat(bracket("price", "gte", "10"), is(condition("price", FilterOperator.GTE, 10L)));
        assertThat(bracket("price", "lt", "10"), is(condition("price", FilterOperator.LT, 10L)));
        assertThat(bracket("price", "lte", "10"), is(condition("price", FilterOperator.LTE, 10L)));
        assertThat(bracket("price", "in", "[1.5,2]"), is(condition("price", FilterOperator.IN, List.of(1.5, 2L))));
        assertThat(bracket("price", "nin", "[3]"), is(condition("price", FilterOperator.NIN, List.of(3L))));
        assertThat(bracket("title", "contains", "ell"), is(condition("title", FilterOperator.CONTAINS, "ell")));
        assertThat(bracket("title", "startsWith", "he"), is(condition("title", FilterOperator.STARTS_WITH, "he")));
        assertThat(bracket("cover", "null", ""), is(condition("cover", FilterOperator.NULL, null)));
        assertThat(bracket("cover", "notnull", ""), is(condition("cover", FilterOperator.NOT_NULL, null)));
    }

    @Test
    void valueKeepsEveryColonAfterTheOperator() {
        assertThat(legacy("title:eq:a:b::c"), is(condition("title", FilterOperator.EQ, "a:b::c")));
        assertThat(legacy("title:contains::"), is(condition("title", FilterOperator.CONTAINS, ":")));
        assertThat(bracket("title", "eq", "a:b::c"), is(condition("title", FilterOperator.EQ, "a:b::c")));
    }

    @Test
    void emptyEqValueStaysAllowedAsBefore() {
        assertThat(legacy("title:eq:"), is(condition("title", FilterOperator.EQ, "")));
        assertThat(bracket("title", "eq", ""), is(condition("title", FilterOperator.EQ, "")));
    }

    @Test
    void nullOperatorsAcceptAnEmptyValueInTheLegacyForm() {
        assertThat(legacy("cover:null:"), is(condition("cover", FilterOperator.NULL, null)));
    }

    @Test
    void blankLegacyFilterAndNoBracketKeysYieldNoCondition() {
        assertThat(ListFilterParser.parse(null, Map.of(), POSTS), is(empty()));
        assertThat(ListFilterParser.parse("", Map.of(), POSTS), is(empty()));
        assertThat(ListFilterParser.parse("  ", null, POSTS), is(empty()));
        assertThat(ListFilterParser.toBson(List.of()), is(nullValue()));
    }

    @Test
    void ignoresQueryParametersThatAreNotBracketFilters() {
        Map<String, String> parameters = Map.of(
                "filter", "title:eq:ignored-here",
                "offset", "0",
                "sort", "title:asc",
                "filters", "x");

        assertThat(ListFilterParser.parse(null, parameters, POSTS), is(empty()));
    }

    @Test
    void appliesEveryBracketConditionAndTheLegacyOneTogether() {
        Map<String, String> parameters = new HashMap<>();
        parameters.put("filter[price][gte]", "10");
        parameters.put("filter[price][lt]", "20");
        parameters.put("filter[status][in]", "[\"draft\",\"review\"]");
        parameters.put("filter[cover][notnull]", "");

        List<FilterCondition> conditions = ListFilterParser.parse("title:contains:x", parameters, POSTS);

        assertThat(conditions, contains(
                condition("title", FilterOperator.CONTAINS, "x"),
                condition("cover", FilterOperator.NOT_NULL, null),
                condition("price", FilterOperator.GTE, 10L),
                condition("price", FilterOperator.LT, 20L),
                condition("status", FilterOperator.IN, List.of("draft", "review"))));
    }

    @Test
    void severalConditionsAreAndedInTheBson() {
        Bson bson = ListFilterParser.toBson(List.of(
                condition("price", FilterOperator.GTE, 10L),
                condition("price", FilterOperator.LT, 20L)));

        assertThat(render(bson), is(BsonDocument.parse(
                "{\"$and\": [{\"price\": {\"$gte\": {\"$numberLong\": \"10\"}}}, {\"price\": {\"$lt\": {\"$numberLong\": \"20\"}}}]}")));
    }

    @Test
    void nullMeansMissingNullEmptyStringOrEmptyArray() {
        assertThat(render(ListFilterParser.toBson(List.of(condition("cover", FilterOperator.NULL, null)))),
                is(BsonDocument.parse("{\"$or\": [{\"cover\": null}, {\"cover\": \"\"}, {\"cover\": {\"$size\": 0}}]}")));
        assertThat(render(ListFilterParser.toBson(List.of(condition("cover", FilterOperator.NOT_NULL, null)))),
                is(BsonDocument.parse(
                        "{\"$nor\": [{\"$or\": [{\"cover\": null}, {\"cover\": \"\"}, {\"cover\": {\"$size\": 0}}]}]}")));
    }

    @Test
    void containsAndStartsWithQuoteTheValue() {
        BsonDocument contains = render(ListFilterParser.toBson(List.of(condition("title", FilterOperator.CONTAINS, "a.(b"))));
        BsonDocument startsWith = render(ListFilterParser.toBson(List.of(condition("title", FilterOperator.STARTS_WITH, "a.*"))));

        assertThat(contains.getRegularExpression("title").getPattern(), is("\\Qa.(b\\E"));
        assertThat(contains.getRegularExpression("title").getOptions(), is("i"));
        assertThat(startsWith.getRegularExpression("title").getPattern(), is("^\\Qa.*\\E"));
        assertThat(startsWith.getRegularExpression("title").getOptions(), is("i"));
    }

    @Test
    void temporalValuesAreNormalizedForEveryOperator() {
        assertThat(legacy("publishedAt:gte:2026-01-01T00:00:00+02:00"),
                is(condition("publishedAt", FilterOperator.GTE, "2025-12-31T22:00:00.000Z")));
        assertThat(legacy("createdAt:lt:2026-01-01T00:00:00Z"),
                is(condition("createdAt", FilterOperator.LT, "2026-01-01T00:00:00.000Z")));
        assertThat(legacy("updatedAt:ne:2026-01-01T00:00:00Z"),
                is(condition("updatedAt", FilterOperator.NE, "2026-01-01T00:00:00.000Z")));
        assertThat(legacy("opensAt:gt:09:30"), is(condition("opensAt", FilterOperator.GT, "09:30:00")));
        assertThat(legacy("publishedOn:lte:2026-02-03"), is(condition("publishedOn", FilterOperator.LTE, "2026-02-03")));
        assertThat(bracket("publishedAt", "in", "[\"2026-01-01T01:00:00+01:00\"]"),
                is(condition("publishedAt", FilterOperator.IN, List.of("2026-01-01T00:00:00.000Z"))));
    }

    @Test
    void textAndRelationValuesStayRawStrings() {
        assertThat(legacy("contact:eq:A@Example.com"), is(condition("contact", FilterOperator.EQ, "A@Example.com")));
        assertThat(legacy("link:startsWith:https://"), is(condition("link", FilterOperator.STARTS_WITH, "https://")));
        assertThat(legacy("author:eq:01H-123"), is(condition("author", FilterOperator.EQ, "01H-123")));
        assertThat(legacy("id:in:[\"x\",\"y\"]"), is(condition("id", FilterOperator.IN, List.of("x", "y"))));
    }

    @Test
    void booleanTakesOnlyTrueOrFalse() {
        assertThat(legacy("featured:eq:true"), is(condition("featured", FilterOperator.EQ, true)));
        assertThat(legacy("featured:ne:false"), is(condition("featured", FilterOperator.NE, false)));
        assertThat(legacy("featured:in:[true]"), is(condition("featured", FilterOperator.IN, List.of(true))));
    }

    @Test
    void selectValuesAreCheckedAgainstTheAllowedValues() {
        assertThat(legacy("status:eq:draft"), is(condition("status", FilterOperator.EQ, "draft")));
        assertThat(legacy("tags:ne:a"), is(condition("tags", FilterOperator.NE, "a")));
        assertThat(legacy("tags:nin:[\"a\",\"c\"]"), is(condition("tags", FilterOperator.NIN, List.of("a", "c"))));
        // contains is a substring search, not a value lookup
        assertThat(legacy("status:contains:raf"), is(condition("status", FilterOperator.CONTAINS, "raf")));
    }

    @Test
    void containsKeepsAllItsFieldTypes() {
        for (String field : List.of("title", "contact", "link", "status", "tags", "author", "id")) {
            assertThat(field, legacy(field + ":contains:x").operator(), is(FilterOperator.CONTAINS));
        }
    }

    @Test
    void orderComparisonsWorkOnEveryOrderedType() {
        for (String filter : List.of("price:gt:1", "publishedOn:gte:2026-01-01", "opensAt:lt:10:00",
                "publishedAt:lte:2026-01-01T00:00:00Z", "createdAt:gt:2026-01-01T00:00:00Z",
                "updatedAt:gt:2026-01-01T00:00:00Z")) {
            assertThat(filter, legacy(filter), is(notNullValue()));
        }
    }

    @Test
    void nullOperatorsWorkOnEveryFilterableTypeAndFile() {
        for (String field : List.of("title", "contact", "link", "price", "featured", "publishedOn", "opensAt",
                "publishedAt", "status", "tags", "author", "cover", "id", "createdAt", "updatedAt")) {
            assertThat(field, legacy(field + ":null").operator(), is(FilterOperator.NULL));
            assertThat(field, legacy(field + ":notnull").operator(), is(FilterOperator.NOT_NULL));
        }
    }

    @Test
    void reportsEveryParsingProblemWithTheCondition() {
        assertThat(legacyError("title"), is("filter 'title': must be of the form <field>:<operator>:<value>"));
        assertThat(legacyError("title:eq"), is("filter 'title:eq': must be of the form <field>:<operator>:<value>"));
        assertThat(legacyError(":eq:x"), is("filter ':eq:x': field must not be empty"));
        assertThat(legacyError("nope:eq:x"), is("filter 'nope:eq:x': unknown field 'nope'"));
        assertThat(legacyError("title:like:x"), is("filter 'title:like:x': unknown operator 'like'"));
        assertThat(legacyError("title:startswith:x"), is("filter 'title:startswith:x': unknown operator 'startswith'"));
        assertThat(legacyError("title::x"), is("filter 'title::x': unknown operator ''"));
        assertThat(legacyError("cover:null:x"), is("filter 'cover:null:x': operator 'null' takes no value"));
        assertThat(legacyError("cover:notnull:x"), is("filter 'cover:notnull:x': operator 'notnull' takes no value"));
        assertThat(legacyError("title:contains:"), is("filter 'title:contains:': value for contains must not be empty"));
        assertThat(legacyError("title:startsWith:"), is("filter 'title:startsWith:': value for startsWith must not be empty"));
    }

    @Test
    void reportsMalformedBracketKeys() {
        String expected = "must be of the form filter[<field>][<operator>]=<value>";
        for (String key : List.of("filter[title]", "filter[title][eq][x]", "filter[title]eq]", "filter[title][eq",
                "filter[ti[tle][eq]", "filter[title][eq]x")) {
            assertThat(key, bracketError(key, "v"), is(key + "='v': " + expected));
        }
        assertThat(bracketError("filter[][eq]", "v"), is("filter[][eq]='v': field must not be empty"));
        assertThat(bracketError("filter[title][]", "v"), is("filter[title][]='v': unknown operator ''"));
        assertThat(bracketError("filter[cover][null]", "x"), is("filter[cover][null]='x': operator 'null' takes no value"));
    }

    @Test
    void reportsOperatorsNotAllowedForTheFieldType() {
        assertThat(legacyError("title:gt:a"), is("filter 'title:gt:a': operator 'gt' is not supported for string field 'title'"));
        assertThat(legacyError("contact:gte:a"), is("filter 'contact:gte:a': operator 'gte' is not supported for email field 'contact'"));
        assertThat(legacyError("status:lt:draft"), is("filter 'status:lt:draft': operator 'lt' is not supported for select field 'status'"));
        assertThat(legacyError("featured:lte:true"), is("filter 'featured:lte:true': operator 'lte' is not supported for boolean field 'featured'"));
        assertThat(legacyError("id:gt:x"), is("filter 'id:gt:x': operator 'gt' is not supported for string field 'id'"));
        assertThat(legacyError("price:contains:1"), is("filter 'price:contains:1': operator 'contains' is not supported for number field 'price'"));
        assertThat(legacyError("publishedAt:contains:2026"),
                is("filter 'publishedAt:contains:2026': operator 'contains' is not supported for date & time field 'publishedAt'"));
        assertThat(legacyError("status:startsWith:d"), is("filter 'status:startsWith:d': operator 'startsWith' is not supported for select field 'status'"));
        assertThat(legacyError("author:startsWith:d"), is("filter 'author:startsWith:d': operator 'startsWith' is not supported for relation field 'author'"));
        assertThat(legacyError("cover:eq:x"), is("filter 'cover:eq:x': file field 'cover' only supports null and notnull"));
        assertThat(legacyError("cover:in:[\"x\"]"), is("filter 'cover:in:[\"x\"]': file field 'cover' only supports null and notnull"));
        assertThat(legacyError("payload:eq:x"), is("filter 'payload:eq:x': json field 'payload' is not filterable"));
        assertThat(legacyError("payload:null"), is("filter 'payload:null': json field 'payload' is not filterable"));
    }

    @Test
    void reportsWrongScalarValues() {
        assertThat(legacyError("price:gte:abc"), is("filter 'price:gte:abc': value for number field 'price' is not a number"));
        assertThat(bracketError("filter[price][gte]", "abc"),
                is("filter[price][gte]='abc': value for number field 'price' is not a number"));
        assertThat(legacyError("featured:eq:yes"), is("filter 'featured:eq:yes': value for boolean field 'featured' must be true or false"));
        assertThat(legacyError("featured:eq:TRUE"), is("filter 'featured:eq:TRUE': value for boolean field 'featured' must be true or false"));
        assertThat(legacyError("publishedAt:gt:yesterday"),
                is("filter 'publishedAt:gt:yesterday': value for date & time field 'publishedAt' is not a valid ISO value"));
        assertThat(legacyError("publishedOn:eq:2026-1-1"),
                is("filter 'publishedOn:eq:2026-1-1': value for date field 'publishedOn' is not a valid ISO value"));
        assertThat(legacyError("opensAt:eq:25:00"), is("filter 'opensAt:eq:25:00': value for time field 'opensAt' is not a valid ISO value"));
        assertThat(legacyError("status:eq:publishd"), is("filter 'status:eq:publishd': 'publishd' is not an allowed value of select field 'status'"));
        assertThat(legacyError("tags:ne:z"), is("filter 'tags:ne:z': 'z' is not an allowed value of select field 'tags'"));
    }

    @Test
    void reportsWrongListValues() {
        assertThat(legacyError("price:in:10"), is("filter 'price:in:10': value for in must be a JSON array"));
        assertThat(legacyError("price:nin:[10"), is("filter 'price:nin:[10': value for nin must be a JSON array"));
        assertThat(legacyError("title:in:a,b"), is("filter 'title:in:a,b': value for in must be a JSON array"));
        assertThat(legacyError("price:in:[]"), is("filter 'price:in:[]': list for in must not be empty"));
        assertThat(legacyError("price:in:[10,\"20\"]"),
                is("filter 'price:in:[10,\"20\"]': entry 2 of the list for number field 'price' is not a JSON number"));
        assertThat(legacyError("title:in:[\"a\",1]"),
                is("filter 'title:in:[\"a\",1]': entry 2 of the list for string field 'title' is not a JSON string"));
        assertThat(legacyError("title:in:[null]"),
                is("filter 'title:in:[null]': entry 1 of the list for string field 'title' is not a JSON string"));
        assertThat(legacyError("featured:in:[\"true\"]"),
                is("filter 'featured:in:[\"true\"]': entry 1 of the list for boolean field 'featured' is not a JSON boolean"));
        assertThat(legacyError("status:in:[\"draft\",\"nope\"]"),
                is("filter 'status:in:[\"draft\",\"nope\"]': 'nope' is not an allowed value of select field 'status'"));
        assertThat(legacyError("publishedAt:nin:[\"soon\"]"),
                is("filter 'publishedAt:nin:[\"soon\"]': value for date & time field 'publishedAt' is not a valid ISO value"));
    }

    // The list text is only bounded by the request line; nesting must end in a 400, never a 500
    @Test
    void deeplyNestedListsAreRejectedAsBadRequest() {
        String deep = "[".repeat(4000) + "]".repeat(4000);
        String shallow = "[".repeat(50) + "]".repeat(50);

        assertThat(legacyError("title:in:" + deep), is("filter 'title:in:" + deep.substring(0, 91)
                + "…': value for in must be a JSON array"));
        assertThat(legacyError("title:in:" + shallow),
                endsWith(": entry 1 of the list for string field 'title' is not a JSON string"));
    }

    @Test
    void listEntriesThatAreObjectsNeverReachTheQuery() {
        assertThat(legacyError("title:in:[{\"$ne\":null}]"),
                endsWith(": entry 1 of the list for string field 'title' is not a JSON string"));
        assertThat(legacyError("price:in:[{\"$gt\":0}]"),
                endsWith(": entry 1 of the list for number field 'price' is not a JSON number"));
    }

    // Field names are looked up, never passed through: no operators, paths or _id
    @Test
    void fieldNamesThatLookLikeQuerySyntaxAreUnknownFields() {
        for (String field : List.of("$where", "$or", "title.length", "_id", "author.passwordHash", "title ")) {
            assertThat(field, bracketError("filter[" + field + "][eq]", "x"), endsWith(": unknown field '" + field + "'"));
        }
    }

    @Test
    void acceptsConditionsUpToTheLimit() {
        Map<String, String> parameters = bracketConditions(ListFilterParser.MAX_CONDITIONS - 1);

        assertThat(ListFilterParser.parse("title:eq:x", parameters, POSTS), hasSize(ListFilterParser.MAX_CONDITIONS));
    }

    @Test
    void rejectsMoreConditionsThanTheLimit() {
        Map<String, String> parameters = bracketConditions(ListFilterParser.MAX_CONDITIONS);

        ListFilterParser.InvalidFilterException e = assertThrows(ListFilterParser.InvalidFilterException.class,
                () -> ListFilterParser.parse("title:eq:x", parameters, POSTS));
        assertThat(e.getMessage(), is("too many filter conditions: 21, at most 20 are allowed"));
    }

    @Test
    void rejectsValuesLongerThanTheLimit() {
        String atLimit = "x".repeat(ListFilterParser.MAX_VALUE_LENGTH);
        String overLimit = atLimit + "x";

        assertThat(legacy("title:eq:" + atLimit).value(), is(atLimit));
        assertThat(legacyError("title:eq:" + overLimit), endsWith(": value is longer than 500 characters"));
        assertThat(legacyError("title:contains:" + overLimit), endsWith(": value is longer than 500 characters"));
        assertThat(legacyError("title:in:[\"" + overLimit + "\"]"), endsWith(": value is longer than 500 characters"));
    }

    @Test
    void abbreviatesLongInputInTheMessage() {
        String message = legacyError("title:eq:" + "x".repeat(600));

        assertThat(message, startsWith("filter 'title:eq:" + "x".repeat(91) + "…': "));
    }

    @Test
    void rejectsListsLongerThanTheLimit() {
        String atLimit = numberList(ListFilterParser.MAX_LIST_ENTRIES);
        String overLimit = numberList(ListFilterParser.MAX_LIST_ENTRIES + 1);

        assertThat((List<?>) legacy("price:in:" + atLimit).value(), hasSize(100));
        assertThat(legacyError("price:in:" + overLimit), endsWith(": list for in has 101 entries, at most 100 are allowed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "passwordHash", "passwordSalt", "resetTokenHash", "resetTokenExpiresAt",
            "verifyTokenHash", "verifyTokenExpiresAt"})
    void rejectsNeverReturnedUserFieldsForEveryOperator(String field) {
        for (FilterOperator operator : FilterOperator.values()) {
            String value = operator.takesNoValue() ? "" : operator.takesList() ? "[\"x\"]" : "x";
            String raw = operator.takesNoValue() ? field + ":" + operator.token() : field + ":" + operator.token() + ":" + value;

            ListFilterParser.InvalidFilterException legacyError = assertThrows(ListFilterParser.InvalidFilterException.class,
                    () -> ListFilterParser.parse(raw, Map.of(), USERS));
            ListFilterParser.InvalidFilterException bracketError = assertThrows(ListFilterParser.InvalidFilterException.class,
                    () -> ListFilterParser.parse(null, Map.of("filter[" + field + "][" + operator.token() + "]", value), USERS));

            assertThat(raw, legacyError.getMessage(), endsWith(": field '" + field + "' is never returned and cannot be used"));
            assertThat(raw, bracketError.getMessage(), endsWith(": field '" + field + "' is never returned and cannot be used"));
        }
    }

    @Test
    void otherUserFieldsStayFilterableWithEveryOperatorTheirTypeAllows() {
        for (String field : List.of("username", "email", "role", "nickname", "id")) {
            for (String filter : List.of("eq:x", "ne:x", "in:[\"x\"]", "nin:[\"x\"]", "contains:x", "startsWith:x", "null", "notnull")) {
                assertThat(field + ":" + filter, ListFilterParser.parse(field + ":" + filter, Map.of(), USERS), hasSize(1));
            }
        }
        for (String filter : List.of("joinedAt:gte:2026-01-01T00:00:00Z", "createdAt:lt:2026-01-01T00:00:00Z",
                "updatedAt:in:[\"2026-01-01T00:00:00Z\"]")) {
            assertThat(filter, ListFilterParser.parse(filter, Map.of(), USERS), hasSize(1));
        }
    }

    @Test
    void credentialFieldNamesAreOnlyRejectedOnUsers() {
        CollectionDefinition other = new CollectionDefinition("x", "accounts",
                List.of(new FieldDefinition("password", FieldType.STRING, false, true, null)),
                List.of(), CollectionRules.locked(), false);

        assertThat(ListFilterParser.parse("password:eq:x", Map.of(), other), hasSize(1));
    }

    @Test
    void searchCoversTheIdAndEveryTextField() {
        assertThat(searchedFields(POSTS), is(List.of("id", "title", "contact", "link", "status", "tags", "author")));
    }

    @Test
    void searchOnUsersLeavesOutPassword() {
        assertThat(searchedFields(USERS), is(List.of("id", "username", "email", "role", "nickname")));
    }

    @Test
    void blankSearchYieldsNoCondition() {
        assertThat(ListFilterParser.search("  ", POSTS), is(nullValue()));
    }

    private static List<String> searchedFields(CollectionDefinition definition) {
        BsonDocument search = render(ListFilterParser.search(" term ", definition));
        return search.getArray("$or").stream()
                .map(condition -> {
                    String field = condition.asDocument().getFirstKey();
                    assertThat(field, condition.asDocument().getRegularExpression(field).getPattern(), is("\\Qterm\\E"));
                    return field;
                })
                .toList();
    }

    private static FilterCondition legacy(String raw) {
        List<FilterCondition> conditions = ListFilterParser.parse(raw, Map.of(), POSTS);
        assertThat(conditions, hasSize(1));
        return conditions.getFirst();
    }

    private static FilterCondition bracket(String field, String operator, String value) {
        List<FilterCondition> conditions = ListFilterParser.parse(
                null, Map.of("filter[" + field + "][" + operator + "]", value), POSTS);
        assertThat(conditions, hasSize(1));
        return conditions.getFirst();
    }

    private static String legacyError(String raw) {
        return assertThrows(ListFilterParser.InvalidFilterException.class,
                () -> ListFilterParser.parse(raw, Map.of(), POSTS)).getMessage();
    }

    private static String bracketError(String key, String value) {
        return assertThrows(ListFilterParser.InvalidFilterException.class,
                () -> ListFilterParser.parse(null, Map.of(key, value), POSTS)).getMessage();
    }

    private static FilterCondition condition(String field, FilterOperator operator, Object value) {
        return new FilterCondition(field, operator, value);
    }

    private static Map<String, String> bracketConditions(int count) {
        Map<String, String> parameters = new HashMap<>();
        List<String> operators = List.of("eq", "ne", "contains", "startsWith", "null", "notnull");
        List<String> fields = List.of("title", "contact", "link", "author");
        for (int i = 0; i < count; i++) {
            String operator = operators.get(i % operators.size());
            String field = fields.get(i / operators.size());
            parameters.put("filter[" + field + "][" + operator + "]", operator.endsWith("null") ? "" : "x");
        }
        assertThat(parameters, aMapWithSize(count));
        return parameters;
    }

    private static String numberList(int size) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (int i = 0; i < size; i++) {
            joiner.add(String.valueOf(i));
        }
        return joiner.toString();
    }

    private static BsonDocument render(Bson bson) {
        return bson.toBsonDocument();
    }
}
