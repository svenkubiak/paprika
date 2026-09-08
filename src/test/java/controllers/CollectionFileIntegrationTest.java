package controllers;

import enums.FieldType;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import models.CollectionRules;
import models.FieldDefinition;
import models.FieldOptions;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.UserService;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@ExtendWith({TestRunner.class})
class CollectionFileIntegrationTest {

    @Test
    void ownerCannotDownloadOtherUsersFile() {
        UserService userService = io.mangoo.core.Application.getInstance(UserService.class);
        var collections = io.mangoo.core.Application.getInstance(services.TenantCollectionService.class);

        userService.createUser("file-owner", null, "secret-password-123");
        userService.createUser("file-stranger", null, "secret-password-456");

        String ownerToken = loginToken("file-owner", "secret-password-123");
        String strangerToken = loginToken("file-stranger", "secret-password-456");

        String collection = "docs_file_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("owner", "owner", "auth", "owner", "owner", "owner"),
                List.of(
                new FieldDefinition("title", FieldType.STRING, true, false, null),
                new FieldDefinition(
                        "owner",
                        FieldType.RELATION,
                        false,
                        true,
                        FieldOptions.forRelation("users")),
                new FieldDefinition(
                        "attachment",
                        FieldType.FILE,
                        true,
                        false,
                        FieldOptions.forFile(1024 * 1024, List.of("text/plain"), 1))));

        String boundary = "----paprika-test";
        String multipartBody = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"title\"\r\n\r\n"
                + "hello\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"attachment\"; filename=\"note.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "secret-content\r\n"
                + "--" + boundary + "--\r\n";

        TestResponse create = TestRequest.post("/api/collections/" + collection)
                .withHeader("Authorization", "Bearer " + ownerToken)
                .withHeader("Content-Type", "multipart/form-data; boundary=" + boundary)
                .withStringBody(multipartBody)
                .execute();

        assertThat(create.getStatusCode(), equalTo(201));

        Document record = collections.dataCollection(TenantTestUtils.defaultTenantContext(), collection).find().first();
        assertThat(record, notNullValue());

        String recordId = record.getString("id");

        TestResponse ownerDownload = TestRequest.get("/api/collections/" + collection + "/" + recordId + "/files/attachment")
                .withHeader("Authorization", "Bearer " + ownerToken)
                .execute();
        assertThat(ownerDownload.getStatusCode(), equalTo(200));

        TestResponse strangerDownload = TestRequest.get("/api/collections/" + collection + "/" + recordId + "/files/attachment")
                .withHeader("Authorization", "Bearer " + strangerToken)
                .execute();
        assertThat(strangerDownload.getStatusCode(), equalTo(404));
    }

    private static String loginToken(String username, String password) {
        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(username, password))
                .withContentType("application/json")
                .execute();
        return extractJsonString(login.getContent(), "accessToken");
    }

    private static String extractJsonString(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("Field not found: " + field);
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
