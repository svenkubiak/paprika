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
 * Devices are recognised by a SHA-256 of user agent and client IP, deliberately without a geo
 * lookup; only the hash is stored. Every failure is swallowed: a login must never fail because
 * of an alert.
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

    public void trustCurrentOrigin(String userId, Request request) {
        try {
            systemUserService.rememberAuthOrigin(userId, fingerprint(header(request, USER_AGENT), clientIp(request)));
        } catch (Exception e) {
            LOG.warn("Could not remember the current origin of superadmin {}: {}", userId, e.getMessage());
        }
    }

    // Missing headers collapse onto one fingerprint: that fails towards a missing alert, never a wrong one.
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

    // Proxy headers only: the socket peer is the proxy.
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
