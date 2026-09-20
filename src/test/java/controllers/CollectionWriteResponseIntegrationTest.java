package controllers;

import auth.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.mangoo.utils.JsonUtils;
import io.undertow.util.StatusCodes;
import models.CollectionDefinition;
import models.CollectionRules;
import models.FieldDefinition;
import models.FieldOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import services.UserService;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * POST and PATCH answer with the written record in exactly the shape a GET of the same record
 * returns.
 */
@ExtendWith({TestRunner.class})
class CollectionWriteResponseIntegrationTest {

    @Test
    void createReturnsRecordWithGeneratedIdAndTimestamps() {
        String collection = publicCollection();

        TestResponse create = TestRequest.post("/api/collections/" + collection)
                .withStringBody("{\"title\":\"created record\"}")
                .withContentType("application/json")
                .execute();

        assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));

        JsonNode body = json(create);
        assertThat(body.path("id").asText().isBlank(), equalTo(false));
        assertThat(body.path("title").asText(), equalTo("created record"));
        assertThat(body.path("createdAt").asText().isBlank(), equalTo(false));
        assertThat(body.path("updatedAt").asText().isBlank(), equalTo(false));
    }

    @Test
    void idFromCreateResponseCanBeUsedForAFollowUpRead() {
        String collection = publicCollection();

        TestResponse create = TestRequest.post("/api/collections/" + collection)
                .withStringBody("{\"title\":\"follow up\"}")
                .withContentType("application/json")
                .execute();
        assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));

        String id = json(create).path("id").asText();

        TestResponse read = TestRequest.get("/api/collections/" + collection + "/" + id).execute();

        assertThat(read.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(json(read).path("title").asText(), equalTo("follow up"));
    }

    @Test
    void createResponseIsFieldForFieldIdenticalToTheReadResponse() {
        String collection = publicCollection();

        TestResponse create = TestRequest.post("/api/collections/" + collection)
                .withStringBody("{\"title\":\"identical\"}")
                .withContentType("application/json")
                .execute();
        assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));

        JsonNode created = json(create);
        TestResponse read = TestRequest.get("/api/collections/" + collection + "/" + created.path("id").asText())
                .execute();

        assertThat(read.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(created, equalTo(json(read)));
    }

    @Test
    void updateReturnsUpdatedRecordAndMatchesTheReadResponse() {
        String collection = publicCollection();

        TestResponse create = TestRequest.post("/api/collections/" + collection)
                .withStringBody("{\"title\":\"before\"}")
                .withContentType("application/json")
                .execute();
        JsonNode created = json(create);
        String id = created.path("id").asText();

        TestResponse update = TestRequest.patch("/api/collections/" + collection + "/" + id)
                .withStringBody("{\"title\":\"after\"}")
                .withContentType("application/json")
                .execute();

        assertThat(update.getStatusCode(), equalTo(StatusCodes.OK));

        JsonNode updated = json(update);
        assertThat(updated.path("id").asText(), equalTo(id));
        assertThat(updated.path("title").asText(), equalTo("after"));
        assertThat(updated.path("createdAt").asText(), equalTo(created.path("createdAt").asText()));
        assertThat(
                updated.path("updatedAt").asText().compareTo(created.path("updatedAt").asText()) >= 0,
                equalTo(true));

        TestResponse read = TestRequest.get("/api/collections/" + collection + "/" + id).execute();
        assertThat(updated, equalTo(json(read)));
    }

    @Test
    void userWriteResponsesNeverExposeCredentialFields() {
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        CollectionDefinition original = collections.findDefinition(ctx, "users");
        CollectionRules originalRules = original.rulesOrDefault();

        replaceRules(collections, ctx, original, new CollectionRules("*", "*", "*", "*", "auth", "owner"));

        try {
            String username = "write-response-" + DbUtils.id();
            TestResponse create = TestRequest.post("/api/collections/users")
                    .withStringBody("{\"username\":\"" + username + "\",\"email\":\"" + username
                            + "@example.com\",\"password\":\"secret-password-123\"}")
                    .withContentType("application/json")
                    .execute();

            assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));
            assertThat(create.getContent(), containsString(username));
            assertThat(create.getContent(), not(containsString("passwordHash")));
            assertThat(create.getContent(), not(containsString("passwordSalt")));

            String id = json(create).path("id").asText();

            TestResponse update = TestRequest.patch("/api/collections/users/" + id)
                    .withStringBody("{\"email\":\"updated-" + username + "@example.com\"}")
                    .withContentType("application/json")
                    .execute();

            assertThat(update.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(update.getContent(), containsString("updated-" + username + "@example.com"));
            assertThat(update.getContent(), not(containsString("passwordHash")));
            assertThat(update.getContent(), not(containsString("passwordSalt")));
        } finally {
            replaceRules(collections, ctx, original, originalRules);
        }
    }

    private static void replaceRules(
            TenantCollectionService collections,
            TenantContext ctx,
            CollectionDefinition original,
            CollectionRules rules) {

        collections.replaceDefinition(ctx, new CollectionDefinition(
                original.id(),
                original.name(),
                original.fields(),
                original.indexes(),
                rules,
                original.system()));
    }

    @Test
    void createWithUploadReturnsTheFileReferenceFormOfARead() {
        UserService userService = Application.getInstance(UserService.class);
        String username = "write-upload-" + DbUtils.id();
        userService.createUser(username, null, "secret-password-123");
        String token = loginToken(username, "secret-password-123");

        String collection = "docs_write_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("owner", "owner", "auth", "owner", "owner", "owner"),
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition("owner", FieldType.RELATION, false, true, FieldOptions.forRelation("users")),
                        new FieldDefinition(
                                "attachment",
                                FieldType.FILE,
                                true,
                                false,
                                FieldOptions.forFile(1024 * 1024, List.of("text/plain"), 1))));

        String boundary = "----paprika-write-response";
        String multipartBody = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"title\"\r\n\r\n"
                + "with file\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"attachment\"; filename=\"note.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "file-content\r\n"
                + "--" + boundary + "--\r\n";

        TestResponse create = TestRequest.post("/api/collections/" + collection)
                .withHeader("Authorization", "Bearer " + token)
                .withHeader("Content-Type", "multipart/form-data; boundary=" + boundary)
                .withStringBody(multipartBody)
                .execute();

        assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));

        JsonNode created = json(create);
        JsonNode attachment = created.path("attachment");
        assertThat(attachment.isObject(), equalTo(true));
        assertThat(attachment.path("url").asText().isBlank(), equalTo(false));

        TestResponse read = TestRequest.get("/api/collections/" + collection + "/" + created.path("id").asText())
                .withHeader("Authorization", "Bearer " + token)
                .execute();

        assertThat(read.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(created, equalTo(json(read)));
    }

    @Test
    void createForbiddenByRuleStaysForbiddenAndReturnsNoRecord() {
        String collection = "posts_write_locked_" + DbUtils.id();
        TenantTestUtils.seedCollection(collection, CollectionRules.locked());

        TestResponse create = TestRequest.post("/api/collections/" + collection)
                .withStringBody("{\"title\":\"forbidden\"}")
                .withContentType("application/json")
                .execute();

        assertThat(create.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        assertThat(create.getContent(), not(containsString("createdAt")));

        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        assertThat(
                collections.dataCollection(TenantTestUtils.defaultTenantContext(), collection).countDocuments(),
                equalTo(0L));
    }

    private static String publicCollection() {
        String collection = "posts_write_" + DbUtils.id();
        TenantTestUtils.seedCollection(collection, new CollectionRules("*", "*", "*", "*", "*", "owner"));
        return collection;
    }

    private static JsonNode json(TestResponse response) {
        try {
            JsonNode node = JsonUtils.getMapper().readTree(response.getContent());
            assertThat(node, notNullValue());
            return node;
        } catch (Exception e) {
            throw new IllegalStateException("Response body is not valid JSON: " + response.getContent(), e);
        }
    }

    private static String loginToken(String username, String password) {
        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(username, password))
                .withContentType("application/json")
                .execute();

        try {
            return JsonUtils.getMapper().readTree(login.getContent()).path("accessToken").asText();
        } catch (Exception e) {
            throw new IllegalStateException("Login failed: " + login.getContent(), e);
        }
    }
}
