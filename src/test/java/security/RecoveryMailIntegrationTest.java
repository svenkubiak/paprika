package security;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import jakarta.mail.internet.MimeMessage;
import models.TenantDefinition;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import services.TenantService;
import services.TenantUserService;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.eq;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Recovery mails carry a credential: they must go only to the stored address, and the raw token must
 * appear in neither the HTTP response nor the database. Needs a real SMTP server (GreenMail).
 */
@ExtendWith({TestRunner.class})
class RecoveryMailIntegrationTest {
    private static final String PASSWORD = "recovery-password-aaa-1";
    private static final String NEW_PASSWORD = "recovery-password-new-2";
    private static final String RESET_URL = "https://app.example.test/reset?x=1";
    private static final String VERIFY_URL = "https://app.example.test/verify/{token}";

    private static GreenMail greenMail;
    private static TenantDefinition tenant;
    private static String username;
    private static String email;

    @BeforeAll
    static void setUp() {
        greenMail = new GreenMail(new ServerSetup(3025, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        greenMail.start();

        tenant = TenantTestUtils.defaultTenant();
        Application.getInstance(TenantService.class).update(
                tenant.id(), null, null, null, null, true, true, null, RESET_URL, VERIFY_URL, null);
        tenant = TenantTestUtils.defaultTenant();

        username = "recovery-user-" + DbUtils.id().substring(0, 8);
        email = username + "@example.test";
        Application.getInstance(TenantUserService.class).createUser(tenant, username, email, PASSWORD);
    }

    @AfterAll
    static void tearDown() {
        if (greenMail != null) {
            greenMail.stop();
        }
        Application.getInstance(TenantService.class).update(
                tenant.id(), null, null, null, null, false, false, false, "", "", null);
    }

    @Test
    void theResetLinkGoesToTheStoredAddressAndTheTokenLivesOnlyInTheMail() throws Exception {
        greenMail.purgeEmailFromAllMailboxes();

        TestResponse response = forgotPassword(email);
        assertThat(response.getStatusCode(), equalTo(200));
        assertThat("the response must never carry the token itself",
                response.getContent(), not(containsString("token")));

        MimeMessage message = awaitSingleMail();
        assertThat("the mail must go to the address stored on the user record",
                message.getAllRecipients()[0].toString(), equalTo(email));

        String token = extractToken(body(message));
        assertThat(token, not(emptyOrNullString()));

        Document user = userRecord();
        assertThat("only the hash may be stored, never the token itself",
                user.getString("resetTokenHash"), not(equalTo(token)));
        assertThat(user.toJson(), not(containsString(token)));

        assertThat(resetPassword(token, NEW_PASSWORD).getStatusCode(), equalTo(200));
        assertThat(login(username, NEW_PASSWORD).getStatusCode(), equalTo(200));
        assertThat("a consumed token must not work twice",
                resetPassword(token, "recovery-password-third-3").getStatusCode(), equalTo(400));
    }

    /**
     * The request address only selects a user and is never the recipient; unknown addresses answer 200
     * too, so accounts cannot be probed.
     */
    @Test
    void anAddressNobodyOwnsProducesNoMailWhileCasingDoesNotMatter() throws Exception {
        greenMail.purgeEmailFromAllMailboxes();

        assertThat(forgotPassword("attacker@evil.test").getStatusCode(), equalTo(200));
        assertThat("no user has this address, so nothing may be sent",
                greenMail.waitForIncomingEmail(1000, 1), is(false));

        assertThat(forgotPassword("  " + email.toUpperCase(java.util.Locale.ROOT) + "  ").getStatusCode(),
                equalTo(200));

        MimeMessage message = awaitSingleMail();
        assertThat("a differently cased address must still reach the same user",
                message.getAllRecipients().length, equalTo(1));
        assertThat("and the recipient is always the stored address",
                message.getAllRecipients()[0].toString(), equalTo(email));
    }

    @Test
    void aResetTokenOfOneTenantDoesNotWorkInAnother() throws Exception {
        greenMail.purgeEmailFromAllMailboxes();

        TenantDefinition other = Application.getInstance(TenantService.class)
                .create("Recovery Other", "recovery-other-" + DbUtils.id().substring(0, 8));
        Application.getInstance(TenantService.class).update(
                other.id(), null, null, null, null, true, null, null, RESET_URL, null, null);

        Application.getInstance(TenantUserService.class)
                .createUser(other, username, email, PASSWORD);

        forgotPassword(email);
        MimeMessage message = awaitSingleMail();
        String tokenOfDefaultTenant = extractToken(body(message));

        TestResponse crossTenant = TestRequest.post("/api/auth/password/reset")
                .withStringBody("{\"tenant\":\"" + other.slug() + "\",\"token\":\"" + tokenOfDefaultTenant
                        + "\",\"password\":\"" + NEW_PASSWORD + "\"}")
                .withContentType("application/json")
                .execute();

        assertThat("a token must only be valid inside the tenant that issued it",
                crossTenant.getStatusCode(), equalTo(400));
        assertThat("the account in the other tenant must keep its password",
                loginTo(other.slug(), username, PASSWORD).getStatusCode(), equalTo(200));
    }

    @Test
    void theVerificationLinkUsesTheConfiguredPlaceholderAndCarriesNoCredentials() throws Exception {
        greenMail.purgeEmailFromAllMailboxes();

        TestResponse response = TestRequest.post("/api/auth/verify/request")
                .withStringBody("{\"tenant\":\"" + tenant.slug() + "\",\"email\":\"" + email + "\"}")
                .withContentType("application/json")
                .execute();
        assertThat(response.getStatusCode(), equalTo(200));

        MimeMessage message = awaitSingleMail();
        String body = body(message);

        assertThat("the tenant's {token} placeholder must be substituted",
                body, containsString("https://app.example.test/verify/"));
        assertThat(body, not(containsString("{token}")));

        Document user = userRecord();
        assertThat("a mail must never carry a password hash or the stored token hashes",
                body, not(containsString(user.getString("passwordHash"))));
        assertThat(body, not(containsString(user.getString("verifyTokenHash"))));
    }

    private static MimeMessage awaitSingleMail() throws Exception {
        assertThat("expected a mail to be delivered", greenMail.waitForIncomingEmail(5000, 1), is(true));
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages.length, greaterThanOrEqualTo(1));
        return messages[messages.length - 1];
    }

    private static String body(MimeMessage message) throws Exception {
        return com.icegreen.greenmail.util.GreenMailUtil.getWholeMessage(message);
    }

    private static String extractToken(String body) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:token=|/verify/)([A-Za-z0-9_-]{20,})")
                .matcher(body.replace("=\r\n", "").replace("=3D", "="));
        return matcher.find() ? matcher.group(1) : "";
    }

    private static Document userRecord() {
        return Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), "users")
                .find(eq("username", username))
                .first();
    }

    private static TestResponse forgotPassword(String address) {
        return TestRequest.post("/api/auth/password/forgot")
                .withStringBody("{\"tenant\":\"" + tenant.slug() + "\",\"email\":\"" + address + "\"}")
                .withContentType("application/json")
                .execute();
    }

    private static TestResponse resetPassword(String token, String password) {
        return TestRequest.post("/api/auth/password/reset")
                .withStringBody("{\"tenant\":\"" + tenant.slug() + "\",\"token\":\"" + token
                        + "\",\"password\":\"" + password + "\"}")
                .withContentType("application/json")
                .execute();
    }

    private static TestResponse login(String username, String password) {
        return loginTo(tenant.slug(), username, password);
    }

    private static TestResponse loginTo(String slug, String username, String password) {
        return TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(slug, username, password))
                .withContentType("application/json")
                .execute();
    }

    @SuppressWarnings("unused")
    private static void quiet() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(50);
    }
}
