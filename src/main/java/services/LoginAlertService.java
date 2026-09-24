package services;

import io.mangoo.core.Config;
import io.mangoo.routing.bindings.Request;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Objects;

/**
 * Mails a superadmin when their account is used from a device the account has not been used from
 * before.
 * <p>
 * "Before" is decided by a fingerprint: the SHA-256 of the user agent and the client IP of the
 * request. That is the same idea PocketBase uses for its login alerts, and it is deliberately not a
 * geo lookup - Paprika ships no IP database and must not call a third party on every login. What it
 * recognises is therefore a device on a network, which is what changes when someone else signs in
 * with stolen credentials, and it is also why a dynamic IP or a different browser on the same
 * machine counts as new and produces one additional mail.
 * <p>
 * Only the hash is ever stored. The user agent and address go into the mail and are then dropped,
 * which keeps this independent of the request log's client-info settings: those decide what Paprika
 * <em>keeps</em> about a caller, while nothing is kept here.
 * <p>
 * The alert requires a confirmed address, an enabled switch on the profile and a configured SMTP
 * host. Every failure in here is swallowed: a login must never fail because an alert could not be
 * recorded or sent.
 */
@Singleton
public class LoginAlertService {
    private static final Logger LOG = LogManager.getLogger(LoginAlertService.class);
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String REAL_IP = "X-Real-IP";
    private static final String USER_AGENT = "User-Agent";
    private static final String UNKNOWN = "unknown";

    private final SystemUserService systemUserService;
    private final MailService mailService;
    private final Config config;

    @Inject
    public LoginAlertService(SystemUserService systemUserService, MailService mailService, Config config) {
        this.systemUserService = Objects.requireNonNull(systemUserService, "systemUserService must not be null");
        this.mailService = Objects.requireNonNull(mailService, "mailService must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /**
     * Records a completed superadmin sign-in and alerts the account owner when it came from an
     * origin this account has not been seen on before.
     */
    public void recordLogin(String userId, Request request) {
        try {
            String userAgent = header(request, USER_AGENT);
            String ipAddress = clientIp(request);

            if (!systemUserService.rememberAuthOrigin(userId, fingerprint(userAgent, ipAddress))) {
                return;
            }

            systemUserService.findProfile(userId)
                    .filter(profile -> profile.loginAlertEnabled()
                            && profile.emailVerified()
                            && StringUtils.isNotBlank(profile.email())
                            && StringUtils.isNotBlank(config.getSmtpHost()))
                    .ifPresent(profile -> mailService.sendSuperadminLoginAlert(
                            profile.email(),
                            profile.username(),
                            userAgent,
                            ipAddress,
                            Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()));
        } catch (Exception e) {
            LOG.warn("Could not process the login alert for superadmin {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Marks the device this request comes from as known without sending anything. Used when the
     * alert is switched on, so the session doing the switching does not mail itself about a login
     * that already happened.
     */
    public void trustCurrentOrigin(String userId, Request request) {
        try {
            systemUserService.rememberAuthOrigin(userId, fingerprint(header(request, USER_AGENT), clientIp(request)));
        } catch (Exception e) {
            LOG.warn("Could not remember the current origin of superadmin {}: {}", userId, e.getMessage());
        }
    }

    /**
     * A request behind a proxy that sets neither header and a client that sends no user agent both
     * end up on the same fingerprint. That is the safe direction to fail in: it produces a missing
     * alert, never a wrong one, and an instance without a reverse proxy simply gets device-level
     * granularity instead of device-and-network.
     */
    private String fingerprint(String userAgent, String ipAddress) {
        String source = StringUtils.defaultIfBlank(userAgent, UNKNOWN) + "|" + StringUtils.defaultIfBlank(ipAddress, UNKNOWN);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Read from the proxy headers only, like everywhere else: the socket peer is the proxy. */
    private String clientIp(Request request) {
        String forwarded = header(request, FORWARDED_FOR);
        if (forwarded != null) {
            int comma = forwarded.indexOf(',');
            return StringUtils.trimToNull(comma >= 0 ? forwarded.substring(0, comma) : forwarded);
        }

        return header(request, REAL_IP);
    }

    private String header(Request request, String name) {
        return request == null ? null : StringUtils.trimToNull(request.getHeader(name));
    }
}
