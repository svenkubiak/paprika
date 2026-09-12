package security;

import auth.TenantContext;
import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import models.CollectionRules;
import models.FieldDefinition;
import models.FieldOptions;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.FileStorageService;
import services.TenantCollectionService;
import services.UserService;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static com.mongodb.client.model.Filters.eq;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Authorization and input limits on the file upload path.
 * <p>
 * The authorization matrix covers records; uploads are a second write path with its own rules:
 * a multipart request reaches {@code ApiMultipartFilter} and {@code FileFieldService} before the
 * record is touched, files are written to disk outside the database, and the constraints
 * (mime type, size, count) are the only thing standing between a tenant user and the instance's
 * storage. A denial therefore has to mean two things here: no record change *and* no file on disk.
 * <p>
 * The mime type check is deliberately exercised with a lying client: the declared
 * {@code Content-Type} of a part must not be what the server bases its decision on.
 */
@ExtendWith({TestRunner.class})
class FileUploadAuthorizationIntegrationTest {
    private static final String BOUNDARY = "----paprika-upload-test";
    private static final String OWNER_PASSWORD = "upload-password-aaa-1";
    private static final String STRANGER_PASSWORD = "upload-password-bbb-2";

    private static String ownerToken;
    private static String strangerToken;

    @BeforeAll
    static void setUp() {
        UserService users = Application.getInstance(UserService.class);
        users.createUser("upload-owner", null, OWNER_PASSWORD);
        users.createUser("upload-stranger", null, STRANGER_PASSWORD);

        ownerToken = login("upload-owner", OWNER_PASSWORD);
        strangerToken = login("upload-stranger", STRANGER_PASSWORD);
    }

    // ---------------------------------------------------------------------------------------
    // Authorization
    // ---------------------------------------------------------------------------------------

    @Test
    void aStrangerCanNeitherAttachToNorDeleteFromAnotherUsersRecord() throws IOException {
        String collection = ownerCollection();
        String recordId = createWithAttachment(collection, ownerToken, "owner file");
        long filesBefore = storedFiles();

        TestResponse attach = multipart("PATCH", "/api/collections/" + collection + "/" + recordId,
                strangerToken, part("title", "hijacked"), filePart("attachment", "evil.txt", "text/plain", "evil"));
        assertThat("attaching to a foreign record must be denied", attach.getStatusCode(), equalTo(404));

        TestResponse delete = TestRequest.delete(
                        "/api/collections/" + collection + "/" + recordId + "/files/attachment")
                .withHeader("Authorization", "Bearer " + strangerToken)
                .execute();
        assertThat("deleting a foreign file must be denied", delete.getStatusCode(), equalTo(404));

        assertThat("a denied upload must not leave a file behind", storedFiles(), equalTo(filesBefore));
        assertThat("the record must be untouched", record(collection, recordId).getString("title"),
                equalTo("owner file"));
        assertThat("the attachment must still be there",
                record(collection, recordId).get("attachment"), notNullValue());
    }

    @Test
    void anAnonymousUploadIsDeniedOnALockedCollection() throws IOException {
        String collection = "upload_locked_" + DbUtils.id();
        TenantTestUtils.seedCollection(collection, CollectionRules.locked(), fileSchema(1024, List.of("text/plain"), 1));
        long filesBefore = storedFiles();

        TestResponse create = multipart("POST", "/api/collections/" + collection, null,
                part("title", "anonymous"), filePart("attachment", "note.txt", "text/plain", "content"));

        assertThat(create.getStatusCode(), anyOf(equalTo(401), equalTo(403)));
        assertThat("a denied upload must not leave a file behind", storedFiles(), equalTo(filesBefore));
    }

    // ---------------------------------------------------------------------------------------
    // Input limits - the only thing between a tenant user and the instance storage
    // ---------------------------------------------------------------------------------------

    /** The client declares text/plain but sends html: the server has to detect the real type. */
    @Test
    void theDeclaredContentTypeOfAPartIsNotTrusted() throws IOException {
        String collection = "upload_mime_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "auth", "*", "*", "owner"),
                fileSchema(1024 * 1024, List.of("text/plain"), 1));
        long filesBefore = storedFiles();

        TestResponse create = multipart("POST", "/api/collections/" + collection, ownerToken,
                part("title", "spoofed"),
                filePart("attachment", "note.txt", "text/plain",
                        "<html><body><script>alert(1)</script></body></html>"));

        assertThat("a file whose real type is not allowed must be rejected",
                create.getStatusCode(), equalTo(400));
        assertThat(create.getContent(), containsString("mime type"));
        assertThat(storedFiles(), equalTo(filesBefore));
    }

    @Test
    void anOversizedFileIsRejectedAndNotStored() throws IOException {
        String collection = "upload_size_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "auth", "*", "*", "owner"),
                fileSchema(64, List.of("text/plain"), 1));
        long filesBefore = storedFiles();

        TestResponse create = multipart("POST", "/api/collections/" + collection, ownerToken,
                part("title", "too big"),
                filePart("attachment", "big.txt", "text/plain", "x".repeat(256)));

        assertThat(create.getStatusCode(), equalTo(400));
        assertThat(create.getContent(), containsString("max size"));
        assertThat(storedFiles(), equalTo(filesBefore));
    }

    /**
     * More file parts than a field allows are rejected outright. Partially storing them and
     * answering 201 would hide the loss from the caller, so the request fails instead - and nothing
     * is written to disk.
     */
    @Test
    void moreFilesThanTheFieldAllowsAreRejectedAndNothingIsStored() throws IOException {
        String collection = "upload_count_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "auth", "*", "*", "owner"),
                fileSchema(1024 * 1024, List.of("text/plain"), 1));
        long filesBefore = storedFiles();

        TestResponse create = multipart("POST", "/api/collections/" + collection, ownerToken,
                part("title", "too many"),
                filePart("attachment", "one.txt", "text/plain", "one"),
                filePart("attachment", "two.txt", "text/plain", "two"));

        assertThat(create.getStatusCode(), equalTo(400));
        assertThat("the caller has to learn why, rather than getting a 201 for half the upload",
                create.getContent(), anyOf(containsString("could be read"), containsString("Too many files")));
        assertThat(storedFiles(), equalTo(filesBefore));
        assertThat("nothing may be persisted for a rejected upload",
                Application.getInstance(TenantCollectionService.class)
                        .dataCollection(context(), collection).find(eq("title", "too many")).first(),
                nullValue());
    }

    /** A file field can only be written through multipart, never through plain json. */
    @Test
    void aFileFieldCannotBeSetThroughJson() {
        String collection = "upload_json_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "auth", "*", "*", "owner"),
                fileSchema(1024 * 1024, List.of("text/plain"), 1));

        TestResponse create = TestRequest.post("/api/collections/" + collection)
                .withHeader("Authorization", "Bearer " + ownerToken)
                .withStringBody("{\"title\":\"forged\",\"attachment\":{\"id\":\"../../etc/passwd\",\"name\":\"x\"}}")
                .withContentType("application/json")
                .execute();

        assertThat(create.getStatusCode(), equalTo(400));
        assertThat(create.getContent(), containsString("multipart/form-data"));
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private static String ownerCollection() throws IOException {
        String collection = "upload_owner_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("owner", "owner", "auth", "owner", "owner", "owner"),
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition("owner", FieldType.RELATION, false, true,
                                FieldOptions.forRelation("users")),
                        new FieldDefinition("attachment", FieldType.FILE, false, true,
                                FieldOptions.forFile(1024 * 1024, List.of("text/plain"), 1))));
        return collection;
    }

    private static List<FieldDefinition> fileSchema(long maxSize, List<String> mimeTypes, int maxSelect) {
        return List.of(
                new FieldDefinition("title", FieldType.STRING, true, false, null),
                new FieldDefinition("attachment", FieldType.FILE, false, true,
                        FieldOptions.forFile(maxSize, mimeTypes, maxSelect)));
    }

    private static String createWithAttachment(String collection, String token, String title) throws IOException {
        TestResponse create = multipart("POST", "/api/collections/" + collection, token,
                part("title", title), filePart("attachment", "note.txt", "text/plain", "secret-content"));
        assertThat(create.getStatusCode(), equalTo(201));

        Document record = Application.getInstance(TenantCollectionService.class)
                .dataCollection(context(), collection)
                .find(eq("title", title))
                .first();
        assertThat(record, notNullValue());
        return record.getString("id");
    }

    private static Document record(String collection, String id) {
        return Application.getInstance(TenantCollectionService.class)
                .dataCollection(context(), collection)
                .find(eq("id", id))
                .first();
    }

    /** Number of files currently on disk for this tenant - a denial must not change it. */
    private static long storedFiles() throws IOException {
        Path root = Application.getInstance(FileStorageService.class).root()
                .resolve(context().effectiveTenantId());
        if (!Files.exists(root)) {
            return 0;
        }
        try (Stream<Path> paths = Files.list(root)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private static TenantContext context() {
        return TenantTestUtils.defaultTenantContext();
    }

    private record Part(String body) { }

    private static Part part(String name, String value) {
        return new Part("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }

    private static Part filePart(String name, String fileName, String declaredType, String content) {
        return new Part("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + declaredType + "\r\n\r\n" + content + "\r\n");
    }

    private static TestResponse multipart(String method, String uri, String token, Part... parts) {
        StringBuilder body = new StringBuilder();
        for (Part part : parts) {
            body.append("--").append(BOUNDARY).append("\r\n").append(part.body());
        }
        body.append("--").append(BOUNDARY).append("--\r\n");

        TestResponse request = TestRequest.create(uri, method)
                .withHeader("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .withStringBody(body.toString());

        if (token != null) {
            request = request.withHeader("Authorization", "Bearer " + token);
        }

        return request.execute();
    }

    private static String login(String username, String password) {
        TestResponse response = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(username, password))
                .withContentType("application/json")
                .execute();

        String marker = "\"accessToken\":\"";
        int start = response.getContent().indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("Login failed: " + response.getContent());
        }
        start += marker.length();
        return response.getContent().substring(start, response.getContent().indexOf('"', start));
    }
}
