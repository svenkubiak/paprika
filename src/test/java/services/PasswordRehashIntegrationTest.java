package services;

import com.mongodb.client.MongoCollection;
import constants.CollectionName;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.mangoo.utils.CommonUtils;
import io.undertow.util.StatusCodes;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static com.mongodb.client.model.Filters.eq;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * mangoo verifies a hash with the parameters it was computed with, so a legacy hash (pre-0.47.0)
 * keeps costing 80 MB per login until a successful login, the only moment the plaintext is at hand, replaces it.
 */
@ExtendWith({TestRunner.class})
class PasswordRehashIntegrationTest {
    private static final String PASSWORD = "rehash-password-1234";

    @Test
    void aTenantLoginReplacesALegacyHashAndKeepsTheSession() {
        String username = "rehash-user-" + DbUtils.id();
        String id = String.valueOf(Application.getInstance(UserService.class)
                .createUser(username, null, PASSWORD).get("id"));
        MongoCollection<Document> users = Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), "users");
        String legacySalt = storeLegacyHash(users, id);

        TestResponse first = login(username);
        assertThat(first.getContent(), first.getStatusCode(), equalTo(StatusCodes.OK));

        Document rehashed = users.find(eq("id", id)).first();
        String hash = rehashed.getString("passwordHash");
        assertThat("the legacy hash has been replaced", CommonUtils.needsRehash(hash), is(false));
        assertThat(rehashed.getString("passwordSalt"), not(equalTo(legacySalt)));

        // The password is the same, so this is no credential change: the session survives
        String token = extract(first.getContent(), "accessToken");
        assertThat(TestRequest.get("/api/auth/me").withHeader("Authorization", "Bearer " + token).execute()
                .getStatusCode(), equalTo(StatusCodes.OK));

        assertThat(login(username).getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(users.find(eq("id", id)).first().getString("passwordHash"), equalTo(hash));
    }

    @Test
    void aSuperadminLoginReplacesALegacyHash() {
        SystemUserService systemUsers = Application.getInstance(SystemUserService.class);
        String username = "rehash-admin-" + DbUtils.id().substring(0, 8);
        String id = String.valueOf(systemUsers.createSuperadmin(username, null, PASSWORD).get("id"));
        MongoCollection<Document> accounts = Application.getInstance(TenantDatabaseResolver.class)
                .systemCollection(CollectionName.USERS);

        try {
            storeLegacyHash(accounts, id);

            TestResponse token = TestRequest.post("/api/admin/token")
                    .withStringBody("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}")
                    .withContentType("application/json")
                    .execute();
            assertThat(token.getContent(), token.getStatusCode(), equalTo(StatusCodes.OK));

            assertThat(CommonUtils.needsRehash(accounts.find(eq("id", id)).first().getString("passwordHash")),
                    is(false));
        } finally {
            // Other tests count the superadmins
            utils.AdminTestUtils.removeSuperadmin(id);
        }
    }

    /** The pre-mangoo-10.13 format: raw Base64 Argon2id output with legacy parameters not embedded. */
    private static String storeLegacyHash(MongoCollection<Document> accounts, String id) {
        String salt = CommonUtils.randomString(22);
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withParallelism(2)
                .withMemoryAsKB(80_000)
                .withIterations(6)
                .withSalt(salt.getBytes(StandardCharsets.UTF_8))
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] hash = new byte[32];
        generator.generateBytes(PASSWORD.getBytes(StandardCharsets.UTF_8), hash);

        String legacy = Base64.getEncoder().encodeToString(hash);
        assertThat("the fixture has to be what mangoo treats as legacy", CommonUtils.needsRehash(legacy), is(true));
        accounts.updateOne(eq("id", id), new Document("$set", new Document("passwordSalt", salt)
                .append("passwordHash", legacy)));
        return salt;
    }

    private static TestResponse login(String username) {
        return TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(username, PASSWORD))
                .withContentType("application/json")
                .execute();
    }

    private static String extract(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        start += marker.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
