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
 * Sends the tenant-user recovery emails (password reset, email verification) straight from Paprika
 * over the instance's global SMTP configuration. Delivery is best-effort: {@code Mail.send()} sends
 * asynchronously, and an unconfigured or failed send is logged, never surfaced to the caller, so the
 * auth endpoints can keep their uniform responses. The link is built by the caller and points at the
 * tenant's own app.
 */
@Singleton
public class MailService {
    private static final Logger LOG = LogManager.getLogger(MailService.class);

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
        return config.getString("smtp.from-name", "Paprika");
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
