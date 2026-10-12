package controllers;

import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.core.Config;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionRules;
import models.FieldDefinition;
import models.FieldOptions;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import services.UserService;
import utils.AdminTestUtils;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/** The list query over HTTP: filter[<field>][<operator>], operators, multi-field sort and limits. */
@ExtendWith({TestRunner.class})
class CollectionListQueryIntegrationTest {

    private static final CollectionRules PUBLIC = new CollectionRules("*", "*", "*", "*", "*", "owner");

    @Test
    void everyBracketConditionIsAppliedAndCounted() {
        String collection = productsCollection("list_query_all_");

        TestResponse response = get(collection, "?offset=0&limit=25"
                + "&" + bracket("price", "gte", "10")
                + "&" + bracket("price", "lt", "30")
                + "&" + bracket("status", "in", "[\"published\",\"review\"]")
                + "&" + bracket("cover", "notnull", ""));

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), containsString("\"total\":1"));
        assertThat(response.getContent(), containsString("\"title\":\"keyboard\""));
    }

    @Test
    void theLegacyFormCombinesWithTheBracketForm() {
        String collection = productsCollection("list_query_combined_");

        TestResponse response = get(collection, "?filter=" + encode("title:startsWith:K")
                + "&" + bracket("price", "lte", "20"));

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), containsString("\"total\":1"));
        assertThat(response.getContent(), containsString("\"title\":\"keyboard\""));
    }

    @Test
    void theLegacySingleConditionStillWorksForEveryNewOperator() {
        String collection = productsCollection("list_query_legacy_ops_");

        assertThat(total(get(collection, "?filter=" + encode("price:gt:20"))), is(2));
        assertThat(total(get(collection, "?filter=" + encode("status:ne:draft"))), is(3));
        assertThat(total(get(collection, "?filter=" + encode("price:in:[5,25]"))), is(2));
        assertThat(total(get(collection, "?filter=" + encode("cover:null"))), is(3));
    }

    // curl -g and browsers send the brackets unencoded; Undertow has to accept them
    @Test
    void unencodedBracketsAreAccepted() throws IOException {
        String collection = productsCollection("list_query_raw_");

        String response = rawGet("/api/collections/" + collection + "?filter[price][gte]=40&filter[cover][null]=");

        assertThat(response, startsWith("HTTP/1.1 200"));
        assertThat(response, containsString("\"total\":1"));
        assertThat(response, containsString("\"title\":\"monitor\""));
    }

    /** mangoo refuses a repeated query key outright, so a second condition is never silently dropped. */
    @Test
    void aRepeatedFilterKeyIsRejectedRatherThanDropped() throws IOException {
        String collection = productsCollection("list_query_repeat_");

        TestResponse legacy = get(collection, "?filter=" + encode("price:gt:1") + "&filter=" + encode("price:lt:2"));
        TestResponse sameBracket = get(collection, "?" + bracket("price", "gt", "1") + "&" + bracket("price", "gt", "2"));
        // Encoded and plain spelling are the same key once decoded
        String mixedSpelling = rawGet("/api/collections/" + collection
                + "?filter%5Bprice%5D%5Bgt%5D=1&filter[price][gt]=2");

        assertThat(legacy.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(sameBracket.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(mixedSpelling, startsWith("HTTP/1.1 400"));
    }

    @Test
    void anInvalidConditionNamesItselfInTheError() {
        String collection = productsCollection("list_query_error_");

        TestResponse number = get(collection, "?" + bracket("price", "gte", "abc"));
        TestResponse select = get(collection, "?" + bracket("status", "eq", "publishd"));
        TestResponse operator = get(collection, "?" + bracket("title", "gt", "a"));

        assertThat(number.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(number.getContent(), containsString(
                "{\"error\":\"filter[price][gte]='abc': value for number field 'price' is not a number\"}"));
        assertThat(select.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(select.getContent(), containsString(
                "filter[status][eq]='publishd': 'publishd' is not an allowed value of select field 'status'"));
        assertThat(operator.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(operator.getContent(), containsString(
                "filter[title][gt]='a': operator 'gt' is not supported for string field 'title'"));
    }

    /** Before: an unknown SELECT value returned an empty list. Now it is a typo the client hears about. */
    @Test
    void anUnknownSelectValueIsABadRequestInTheLegacyFormToo() {
        String collection = productsCollection("list_query_select_typo_");

        TestResponse typo = get(collection, "?filter=" + encode("status:eq:publishd"));
        TestResponse known = get(collection, "?filter=" + encode("status:eq:published"));

        assertThat(typo.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(typo.getContent(), containsString("'publishd' is not an allowed value of select field 'status'"));
        assertThat(known.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(total(known), is(2));
    }

    @Test
    void limitsAreEnforcedOverHttp() {
        String collection = productsCollection("list_query_limits_");

        StringBuilder tooMany = new StringBuilder("?offset=0");
        for (String field : List.of("title", "status", "price")) {
            String scalar = field.equals("price") ? "1" : field.equals("status") ? "draft" : "x";
            String list = "[" + (field.equals("price") ? "1" : "\"" + scalar + "\"") + "]";
            tooMany.append('&').append(bracket(field, "null", ""))
                    .append('&').append(bracket(field, "notnull", ""))
                    .append('&').append(bracket(field, "eq", scalar))
                    .append('&').append(bracket(field, "ne", scalar))
                    .append('&').append(bracket(field, "in", list))
                    .append('&').append(bracket(field, "nin", list));
        }
        tooMany.append('&').append(bracket("cover", "null", ""))
                .append('&').append(bracket("cover", "notnull", ""))
                .append('&').append(bracket("title", "contains", "x"));

        TestResponse conditions = get(collection, tooMany.toString());
        TestResponse longValue = get(collection, "?" + bracket("title", "eq", "x".repeat(501)));

        assertThat(conditions.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(conditions.getContent(), containsString("too many filter conditions: 21, at most 20 are allowed"));
        assertThat(longValue.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(longValue.getContent(), containsString("value is longer than 500 characters"));
    }

    // "+" in a query string decodes to a space, so an offset has to be sent as %2B
    @Test
    void timestampRangesWorkAcrossOffsets() {
        String collection = "list_query_range_" + DbUtils.id();
        TenantTestUtils.seedCollection(collection, PUBLIC, List.of(
                new FieldDefinition("title", FieldType.STRING, true, false, null),
                new FieldDefinition("publishedAt", FieldType.DATETIME, false, true, null)));
        insert(collection, new Document("id", DbUtils.id()).append("title", "before").append("publishedAt", "2025-12-31T21:00:00.000Z"));
        insert(collection, new Document("id", DbUtils.id()).append("title", "inside").append("publishedAt", "2025-12-31T23:00:00.000Z"));
        insert(collection, new Document("id", DbUtils.id()).append("title", "after").append("publishedAt", "2026-01-01T05:00:00.000Z"));

        TestResponse encoded = get(collection, "?" + bracket("publishedAt", "gte", "2026-01-01T00:00:00+02:00")
                + "&" + bracket("publishedAt", "lt", "2025-12-31T19:00:00-05:00"));
        TestResponse plainPlus = get(collection, "?filter%5BpublishedAt%5D%5Bgte%5D=2026-01-01T00:00:00+02:00");

        assertThat(encoded.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(encoded.getContent(), containsString("\"total\":1"));
        assertThat(encoded.getContent(), containsString("\"title\":\"inside\""));
        assertThat(plainPlus.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(plainPlus.getContent(), containsString("is not a valid ISO value"));
    }

    @Test
    void sortsBySeveralFieldsAndPagesStably() {
        String collection = "list_query_sort_" + DbUtils.id();
        TenantTestUtils.seedCollection(collection, PUBLIC, List.of(
                new FieldDefinition("title", FieldType.STRING, true, false, null),
                new FieldDefinition("group", FieldType.NUMBER, false, true, null),
                new FieldDefinition("rank", FieldType.NUMBER, false, true, null)));
        for (int i = 0; i < 23; i++) {
            Document document = new Document("id", DbUtils.id()).append("title", "r" + i).append("group", i % 3);
            if (i % 4 != 0) {
                document.append("rank", i % 2);
            }
            insert(collection, document);
        }

        String sort = "&sort=" + encode("group:asc,rank:desc");
        List<String> full = titles(get(collection, "?offset=0&limit=100" + sort));
        List<String> paged = new ArrayList<>();
        for (int offset = 0; offset < 23; offset += 5) {
            paged.addAll(titles(get(collection, "?offset=" + offset + "&limit=5" + sort)));
        }

        assertThat(full, hasSize(23));
        assertThat(paged, is(full));
        assertThat(new LinkedHashSet<>(paged), hasSize(23));
        // group 0, then the highest rank; equal ranks in insertion (_id) order
        assertThat(full.subList(0, 4), is(List.of("r3", "r9", "r15", "r21")));
    }

    @Test
    void sortErrorsNameTheSort() {
        String collection = productsCollection("list_query_sort_error_");

        TestResponse tooMany = get(collection, "?sort=" + encode("title:asc,price:asc,status:asc,id:asc"));
        TestResponse unknown = get(collection, "?sort=" + encode("title:asc,nope:desc"));

        assertThat(tooMany.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(tooMany.getContent(), containsString("at most 3 sort fields are allowed"));
        assertThat(unknown.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(unknown.getContent(), containsString("sort 'title:asc,nope:desc': unknown field 'nope'"));
    }

    @Test
    void neverReturnedUserFieldsAreRejectedForFilterAndSort() {
        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        for (String field : List.of("password", "passwordHash", "passwordSalt", "resetTokenHash",
                "resetTokenExpiresAt", "verifyTokenHash", "verifyTokenExpiresAt")) {
            TestResponse legacy = AdminTestUtils.getWithAdminCookies(
                    "/api/collections/users?filter=" + encode(field + ":eq:x"), cookies);
            TestResponse bracketForm = AdminTestUtils.getWithAdminCookies(
                    "/api/collections/users?" + bracket(field, "notnull", ""), cookies);
            TestResponse sort = AdminTestUtils.getWithAdminCookies(
                    "/api/collections/users?sort=" + encode(field + ":asc"), cookies);

            String expected = "field '" + field + "' is never returned and cannot be used";
            assertThat(field, legacy.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
            assertThat(field, legacy.getContent(), containsString(expected));
            assertThat(field, bracketForm.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
            assertThat(field, bracketForm.getContent(), containsString(expected));
            assertThat(field, sort.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
            assertThat(field, sort.getContent(), containsString(expected));
        }
    }

    @Test
    void otherUserFieldsStayFilterableAndSortable() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("list-query-lookup", "lookup@example.com", "secret-password-123");
        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        for (String query : List.of(
                "filter=" + encode("username:eq:list-query-lookup"),
                bracket("username", "startsWith", "list-query-look"),
                bracket("email", "eq", "lookup@example.com") + "&" + bracket("email", "notnull", ""),
                bracket("username", "in", "[\"list-query-lookup\"]") + "&" + bracket("role", "ne", "nobody"))) {
            TestResponse response = AdminTestUtils.getWithAdminCookies(
                    "/api/collections/users?sort=" + encode("role:asc,username:desc") + "&" + query, cookies);

            assertThat(query, response.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(query, response.getContent(), containsString("\"total\":1"));
            assertThat(query, response.getContent(), containsString("list-query-lookup"));
            assertThat(query, response.getContent(), not(containsString("passwordHash")));
        }
    }

    @Test
    void filtersNarrowUnderTheAuthPreset() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("list-query-auth", null, "secret-password-123");
        String token = loginToken("list-query-auth", "secret-password-123");

        String collection = "list_query_auth_" + DbUtils.id();
        TenantTestUtils.seedCollection(collection, new CollectionRules("auth", "auth", "auth", "auth", "auth", "owner"));
        TenantTestUtils.seedRecord(collection, "keep");
        TenantTestUtils.seedRecord(collection, "drop");

        TestResponse guest = get(collection, "?" + bracket("title", "notnull", ""));
        TestResponse user = TestRequest.get("/api/collections/" + collection + "?" + bracket("title", "ne", "drop"))
                .withHeader("Authorization", "Bearer " + token)
                .execute();

        assertThat(guest.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        assertThat(user.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(user.getContent(), containsString("\"total\":1"));
        assertThat(user.getContent(), containsString("keep"));
    }

    @Test
    void filtersNarrowUnderTheOwnerPreset() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("list-query-owner-a", null, "secret-password-123");
        userService.createUser("list-query-owner-b", null, "secret-password-456");
        String tokenA = loginToken("list-query-owner-a", "secret-password-123");
        String tokenB = loginToken("list-query-owner-b", "secret-password-456");

        String collection = "list_query_owner_" + DbUtils.id();
        TenantTestUtils.seedCollection(collection,
                new CollectionRules("owner", "owner", "auth", "owner", "owner", "owner"),
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition("owner", FieldType.RELATION, false, true, FieldOptions.forRelation("users"))));
        createRecord(collection, tokenA, "A-one");
        createRecord(collection, tokenA, "A-two");
        createRecord(collection, tokenB, "B-one");

        for (String query : List.of(bracket("id", "notnull", ""), bracket("title", "nin", "[\"nothing\"]"),
                bracket("owner", "notnull", ""))) {
            TestResponse response = TestRequest.get("/api/collections/" + collection + "?" + query)
                    .withHeader("Authorization", "Bearer " + tokenA)
                    .execute();

            assertThat(query, response.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(query, response.getContent(), containsString("\"total\":2"));
            assertThat(query, response.getContent(), not(containsString("B-one")));
        }
    }

    @Test
    void filtersNeverOpenTheLockedPreset() {
        String collection = "list_query_locked_" + DbUtils.id();
        TenantTestUtils.seedCollection(collection, CollectionRules.locked());
        TenantTestUtils.seedRecord(collection, "hidden");
        Application.getInstance(UserService.class).createUser("list-query-locked", null, "secret-password-123");
        String token = loginToken("list-query-locked", "secret-password-123");

        TestResponse response = TestRequest.get("/api/collections/" + collection + "?" + bracket("id", "notnull", ""))
                .withHeader("Authorization", "Bearer " + token)
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
        assertThat(response.getContent(), not(containsString("hidden")));
    }

    // price 5 draft, 15 published+cover, 25 published, 45 review
    private static String productsCollection(String prefix) {
        String collection = prefix + DbUtils.id();
        TenantTestUtils.seedCollection(collection, PUBLIC, List.of(
                new FieldDefinition("title", FieldType.STRING, true, false, null),
                new FieldDefinition("price", FieldType.NUMBER, false, true, null),
                new FieldDefinition("status", FieldType.SELECT, false, true,
                        new FieldOptions(null, null, null, 1, List.of("draft", "review", "published"))),
                new FieldDefinition("cover", FieldType.FILE, false, true, null)));
        insert(collection, new Document("id", DbUtils.id()).append("title", "mouse").append("price", 5).append("status", "draft"));
        insert(collection, new Document("id", DbUtils.id()).append("title", "keyboard").append("price", 15)
                .append("status", "published").append("cover", new Document("id", "f1").append("name", "k.png")));
        insert(collection, new Document("id", DbUtils.id()).append("title", "cable").append("price", 25).append("status", "published"));
        insert(collection, new Document("id", DbUtils.id()).append("title", "monitor").append("price", 45).append("status", "review"));
        return collection;
    }

    private static String bracket(String field, String operator, String value) {
        return encode("filter[" + field + "][" + operator + "]") + "=" + encode(value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static TestResponse get(String collection, String query) {
        return TestRequest.get("/api/collections/" + collection + query).execute();
    }

    private static int total(TestResponse response) {
        assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.OK));
        String content = response.getContent();
        int start = content.indexOf("\"total\":") + "\"total\":".length();
        int end = start;
        while (end < content.length() && Character.isDigit(content.charAt(end))) {
            end++;
        }
        return Integer.parseInt(content.substring(start, end));
    }

    private static List<String> titles(TestResponse response) {
        assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.OK));
        List<String> titles = new ArrayList<>();
        String content = response.getContent();
        int index = content.indexOf("\"title\":\"");
        while (index >= 0) {
            int start = index + "\"title\":\"".length();
            int end = content.indexOf('"', start);
            titles.add(content.substring(start, end));
            index = content.indexOf("\"title\":\"", end);
        }
        return titles;
    }

    private static String rawGet(String pathAndQuery) throws IOException {
        Config config = Application.getInstance(Config.class);
        try (Socket socket = new Socket(config.getConnectorHttpHost(), config.getConnectorHttpPort())) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + pathAndQuery + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            try (InputStream in = socket.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private static void insert(String collection, Document document) {
        Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), collection)
                .insertOne(document);
    }

    private static void createRecord(String collection, String token, String title) {
        TestResponse create = TestRequest.post("/api/collections/" + collection)
                .withHeader("Authorization", "Bearer " + token)
                .withStringBody("{\"title\":\"" + title + "\"}")
                .withContentType("application/json")
                .execute();
        assertThat(create.getContent(), create.getStatusCode(), equalTo(StatusCodes.CREATED));
    }

    private static String loginToken(String username, String password) {
        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(username, password))
                .withContentType("application/json")
                .execute();
        String marker = "\"accessToken\":\"";
        int start = login.getContent().indexOf(marker) + marker.length();
        return login.getContent().substring(start, login.getContent().indexOf('"', start));
    }
}
