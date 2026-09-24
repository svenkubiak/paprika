package services;

import io.mangoo.core.Config;
import io.mangoo.email.Mail;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;

/**
 * Sends the mails Paprika produces itself - tenant-user recovery (password reset, email
 * verification) and the superadmin mails (invite, address confirmation, login alert) - over the
 * instance's global SMTP configuration. Delivery is best-effort: {@code Mail.send()} sends
 * asynchronously, and an unconfigured or failed send is logged, never surfaced to the caller, so the
 * auth endpoints can keep their uniform responses. Recovery links are built by the caller and point
 * at the tenant's own app.
 */
@Singleton
public class MailService {
    private static final Logger LOG = LogManager.getLogger(MailService.class);

    /**
     * Underscore rather than hyphen on purpose: mangoo derives the environment variable of a
     * config key by upper-casing it and replacing dots with underscores, and nothing else. A
     * key named {@code smtp.from-name} would derive {@code SMTP_FROM-NAME}, which no POSIX
     * shell can set - the {@code SMTP_FROM_NAME} the installers write would never arrive.
     */
    public static final String SMTP_FROM_NAME_KEY = "smtp.from_name";

    private final Config config;

    @Inject
    public MailService(Config config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    public void sendPasswordReset(String toEmail, String link, String username) {
        String greeting = StringUtils.isNotBlank(username) ? username : "there";
        String text = "Hi " + greeting + ",\n\n"
                + "We received a request to reset your password. Open the link below to choose a new "
                + "one. It expires in 30 minutes.\n\n"
                + link + "\n\n"
                + "If you didn't request this, you can ignore this email.";
        String html = htmlBody(
                "Reset your password",
                "We received a request to reset your password. Use the button below to choose a new "
                        + "one. This link expires in 30 minutes.",
                "Reset password",
                link,
                "If you didn't request this, you can ignore this email.");
        send(toEmail, "Reset your password", text, html);
    }

    public void sendEmailVerification(String toEmail, String link, String username) {
        String greeting = StringUtils.isNotBlank(username) ? username : "there";
        String text = "Hi " + greeting + ",\n\n"
                + "Please confirm your email address by opening the link below. It expires in 30 "
                + "minutes.\n\n"
                + link + "\n\n"
                + "If you didn't create this account, you can ignore this email.";
        String html = htmlBody(
                "Verify your email",
                "Please confirm your email address using the button below. This link expires in 30 "
                        + "minutes.",
                "Verify email",
                link,
                "If you didn't create this account, you can ignore this email.");
        send(toEmail, "Verify your email", text, html);
    }

    public void sendSuperadminInvite(String toEmail, String link, String username) {
        String greeting = StringUtils.isNotBlank(username) ? username : "there";
        String text = "Hi " + greeting + ",\n\n"
                + "You've been invited to administer this Paprika instance. Open the link below to set "
                + "your password and finish setup. It expires in 30 minutes.\n\n"
                + link + "\n\n"
                + "If you weren't expecting this, you can ignore this email.";
        String html = htmlBody(
                "You're invited as a superadmin",
                "You've been invited to administer this Paprika instance. Use the button below to set "
                        + "your password and finish setup. This link expires in 30 minutes.",
                "Set up your account",
                link,
                "If you weren't expecting this, you can ignore this email.");
        send(toEmail, "You've been invited as a Paprika superadmin", text, html);
    }

    /**
     * Confirms the address a superadmin stored on their own profile. Deliberately worded for the
     * admin control plane rather than reusing the tenant-user wording: the account this confirms is
     * the one that operates the whole instance.
     */
    public void sendSuperadminEmailVerification(String toEmail, String link, String username) {
        String greeting = StringUtils.isNotBlank(username) ? username : "there";
        String text = "Hi " + greeting + ",\n\n"
                + "Please confirm this address for your Paprika superadmin account by opening the link "
                + "below. It expires in 30 minutes.\n\n"
                + link + "\n\n"
                + "Until it is confirmed, Paprika will not send anything else to this address. If you "
                + "didn't add it, you can ignore this email.";
        String html = htmlBody(
                "Confirm your email address",
                "Please confirm this address for your Paprika superadmin account using the button "
                        + "below. This link expires in 30 minutes.",
                "Confirm address",
                link,
                "Until it is confirmed, Paprika will not send anything else to this address. If you "
                        + "didn't add it, you can ignore this email.");
        send(toEmail, "Confirm your Paprika superadmin email address", text, html);
    }

    /**
     * Tells a superadmin that their account was used from a device Paprika has not seen before.
     * The user agent and IP address are only passed through into this mail - neither is stored, the
     * account only keeps a hash of the two.
     */
    public void sendSuperadminLoginAlert(
            String toEmail,
            String username,
            String userAgent,
            String ipAddress,
            String signedInAt) {

        String greeting = StringUtils.isNotBlank(username) ? username : "there";
        String device = StringUtils.isNotBlank(userAgent) ? userAgent : "unknown";
        String address = StringUtils.isNotBlank(ipAddress) ? ipAddress : "unknown";

        String text = "Hi " + greeting + ",\n\n"
                + "Your Paprika superadmin account was just signed in from a device or location it "
                + "has not been used from before.\n\n"
                + "Time: " + signedInAt + "\n"
                + "IP address: " + address + "\n"
                + "Browser: " + device + "\n\n"
                + "If that was you, nothing needs to happen. If it wasn't, change your password "
                + "immediately and enable two-factor authentication.";
        String html = "<!doctype html><html><body style=\"font-family:sans-serif;line-height:1.5;color:#111\">"
                + "<h2>New sign-in to your superadmin account</h2>"
                + "<p>Your Paprika superadmin account was just signed in from a device or location it "
                + "has not been used from before.</p>"
                + "<table style=\"font-size:14px;border-collapse:collapse\">"
                + row("Time", signedInAt)
                + row("IP address", address)
                + row("Browser", device)
                + "</table>"
                + "<p style=\"color:#777;font-size:13px\">If that was you, nothing needs to happen. If it "
                + "wasn't, change your password immediately and enable two-factor authentication.</p>"
                + "</body></html>";

        send(toEmail, "New sign-in to your Paprika superadmin account", text, html);
    }

    private static String row(String label, String value) {
        return "<tr><td style=\"padding:2px 12px 2px 0;color:#555\">" + label + "</td>"
                + "<td style=\"padding:2px 0;word-break:break-all\">" + escape(value) + "</td></tr>";
    }

    /**
     * The user agent is attacker-controlled and ends up in an HTML mail, so it is escaped rather
     * than trusted. It is the only value in these mails that does not come from Paprika itself.
     */
    private static String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private void send(String toEmail, String subject, String text, String html) {
        if (StringUtils.isBlank(toEmail)) {
            return;
        }
        if (StringUtils.isBlank(config.getSmtpHost())) {
            LOG.warn("SMTP is not configured (smtp.host is empty); skipping '{}' email", subject);
            return;
        }

        try {
            Mail.newMail()
                    .from(fromName(), fromAddress())
                    .to(toEmail)
                    .subject(subject)
                    .textMessage(text)
                    .htmlMessage(html)
                    .send();
        } catch (Exception e) {
            LOG.warn("Failed to send '{}' email: {}", subject, e.getMessage());
        }
    }

    private String fromAddress() {
        return config.getSmtpFrom();
    }

    private String fromName() {
        return config.getString(SMTP_FROM_NAME_KEY, "Paprika");
    }

    private static String htmlBody(String heading, String intro, String action, String link, String footer) {
        return "<!doctype html><html><body style=\"font-family:sans-serif;line-height:1.5;color:#111\">"
                + "<h2>" + heading + "</h2>"
                + "<p>" + intro + "</p>"
                + "<p><a href=\"" + link + "\" style=\"display:inline-block;padding:10px 16px;"
                + "background:#111;color:#fff;text-decoration:none;border-radius:6px\">" + action + "</a></p>"
                + "<p style=\"word-break:break-all;color:#555;font-size:13px\">" + link + "</p>"
                + "<p style=\"color:#777;font-size:13px\">" + footer + "</p>"
                + "</body></html>";
    }
}
