package security;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.SystemUserService;
import utils.AdminTestUtils;

import java.net.HttpCookie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * The superadmin login alert end to end, against a real SMTP server: confirming the address, then
 * a sign-in from a device the account has not been used from before.
 * <p>
 * Two properties matter and are asserted here. The alert only fires for an <b>unknown</b> device,
 * so working from the same browser stays quiet - an alert on every login would be ignored within a
 * day and protect nothing. And the mail must carry no credential: it reports a sign-in, it does not
 * enable one.
 */
@ExtendWith({TestRunner.class})
class SuperadminLoginAlertIntegrationTest {
    private static final String EMAIL = "login-alert-admin@example.test";
    private static final String KNOWN_AGENT = "Paprika-Test-Known/1.0";
    private static final String KNOWN_IP = "203.0.113.10";
    private static final String OTHER_AGENT = "Paprika-Test-Stranger/1.0";
    private static final String OTHER_IP = "198.51.100.77";

    private static GreenMail greenMail;

    @BeforeAll
    static void setUp() {
        greenMail = new GreenMail(new ServerSetup(3025, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        greenMail.start();
    }

    @AfterAll
    static void tearDown() {
        if (greenMail != null) {
            greenMail.stop();
        }

        SystemUserService users = Application.getInstance(SystemUserService.class);
        String adminId = String.valueOf(users.findPublicUserByUsername("admin").orElseThrow().get("id"));
        users.setLoginAlertEnabled(adminId, false);
        users.clearEmail(adminId);
    }

    @Test
    void anUnknownDeviceIsReportedWhileAKnownOneStaysQuiet() throws Exception {
        greenMail.purgeEmailFromAllMailboxes();

        String password = AdminTestUtils.prepareAdminPassword();
        HttpCookie auth = AdminTestUtils.loginAsAdmin();

        // 1. Store the address and confirm it with the token that only exists in the mail
        TestResponse stored = postJson("/api/admin/profile/email", auth, "{\"email\":\"" + EMAIL + "\"}");
        assertThat(stored.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat("the response must never carry the confirmation token",
                stored.getContent(), not(containsString("verify-email#token=")));

        MimeMessage verification = awaitLatestMail();
        assertThat(verification.getAllRecipients()[0].toString(), equalTo(EMAIL));

        TestResponse confirmed = TestRequest.post("/api/admin/verify-email")
                .withStringBody("{\"token\":\"" + extractToken(body(verification)) + "\"}")
                .withContentType("application/json")
                .execute();
        assertThat(confirmed.getStatusCode(), equalTo(StatusCodes.OK));

        // 2. Switching the alert on trusts the device doing the switching, so the session that
        //    turned it on does not immediately mail itself about its own login
        greenMail.purgeEmailFromAllMailboxes();
        TestResponse enabled = TestRequest.post("/api/admin/profile/login-alert")
                .withCookie(auth)
                .withHeader("User-Agent", KNOWN_AGENT)
                .withHeader("X-Forwarded-For", KNOWN_IP)
                .withStringBody("{\"enabled\":true}")
                .withContentType("application/json")
                .execute();
        assertThat(enabled.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(enabled.getContent(), containsString("\"loginAlertEnabled\":true"));

        assertThat("signing in from the device that enabled the alert must stay quiet",
                login(password, KNOWN_AGENT, KNOWN_IP).getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(greenMail.waitForIncomingEmail(1000, 1), is(false));

        // 3. A different browser on a different network is a device this account has not been
        //    used from, and that is exactly what the alert exists for
        assertThat(login(password, OTHER_AGENT, OTHER_IP).getStatusCode(), equalTo(StatusCodes.OK));

        MimeMessage alert = awaitLatestMail();
        assertThat("the alert goes to the address stored on the account",
                alert.getAllRecipients()[0].toString(), equalTo(EMAIL));

        String body = body(alert);
        assertThat(body, containsString(OTHER_AGENT));
        assertThat("a mail about a sign-in must never carry anything that enables one",
                body, not(containsString(password)));
        assertThat(body, not(containsString("token=")));

        // 4. That device is now known as well, so it only ever produces one mail
        greenMail.purgeEmailFromAllMailboxes();
        assertThat(login(password, OTHER_AGENT, OTHER_IP).getStatusCode(), equalTo(StatusCodes.OK));
        assertThat("a device is reported once, not on every login",
                greenMail.waitForIncomingEmail(1000, 1), is(false));
    }

    private static TestResponse login(String password, String userAgent, String ipAddress) {
        return TestRequest.post("/api/admin/login")
                .withHeader("User-Agent", userAgent)
                .withHeader("X-Forwarded-For", ipAddress)
                .withStringBody("{\"username\":\"admin\",\"password\":\"" + password + "\"}")
                .withContentType("application/json")
                .execute();
    }

    private static TestResponse postJson(String uri, HttpCookie auth, String body) {
        return TestRequest.post(uri)
                .withCookie(auth)
                .withStringBody(body)
                .withContentType("application/json")
                .execute();
    }

    private static MimeMessage awaitLatestMail() {
        assertThat("expected a mail to be delivered", greenMail.waitForIncomingEmail(5000, 1), is(true));
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages.length, greaterThanOrEqualTo(1));
        return messages[messages.length - 1];
    }

    private static String body(MimeMessage message) throws Exception {
        return GreenMailUtil.getWholeMessage(message);
    }

    /** The confirmation link carries its token in the fragment, exactly like the setup link does. */
    private static String extractToken(String body) {
        Matcher matcher = Pattern.compile("verify-email#token=([A-Za-z0-9_-]{20,})")
                .matcher(body.replace("=\r\n", "").replace("=3D", "="));
        assertThat("the mail must carry a confirmation link", matcher.find(), is(true));
        return matcher.group(1);
    }
}
