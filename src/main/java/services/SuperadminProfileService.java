package services;

import io.mangoo.exceptions.MangooHashingException;
import auth.AuthContext;
import dtos.ChangePasswordDto;
import dtos.LoginAlertDto;
import dtos.ProfileEmailDto;
import dtos.TwoFactorCodeDto;
import dtos.TwoFactorSetupDto;
import dtos.UpdateAvatarDto;
import dtos.VerifyEmailDto;
import io.mangoo.core.Config;
import io.mangoo.routing.bindings.Request;
import io.undertow.util.Headers;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.commons.lang3.StringUtils;
import results.AdminSettingsResult;
import results.SuperadminPasswordResult;
import services.SystemUserService.SuperadminProfile;
import session.PendingTwoFactorSession;
import utils.InstanceLinks;
import utils.MimeTypes;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Acts only on the account behind the current session and never takes a user id from the
 * request, so one superadmin can never edit another's profile.
 */
@Singleton
public class SuperadminProfileService {
    private static final int MAX_AVATAR_BYTES = 512 * 1024;
    private static final Set<String> AVATAR_MIME_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    private static final Pattern DATA_URL = Pattern.compile("^data:([a-z0-9.+/-]+);base64,([A-Za-z0-9+/=\\s]+)$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$");

    private final SystemUserService systemUserService;
    private final TwoFactorService twoFactorService;
    private final AuthService authService;
    private final MailService mailService;
    private final LoginAlertService loginAlertService;
    private final TokenVersionService tokenVersionService;
    private final Config config;

    @Inject
    public SuperadminProfileService(
            SystemUserService systemUserService,
            TwoFactorService twoFactorService,
            AuthService authService,
            MailService mailService,
            LoginAlertService loginAlertService,
            TokenVersionService tokenVersionService,
            Config config) {
        this.systemUserService = systemUserService;
        this.twoFactorService = twoFactorService;
        this.authService = authService;
        this.mailService = mailService;
        this.loginAlertService = loginAlertService;
        this.tokenVersionService = tokenVersionService;
        this.config = config;
    }

    public AdminSettingsResult read(Request request) {
        return withProfile(request, (userId, profile) -> AdminSettingsResult.ok(toPayload(profile)));
    }

    public AdminSettingsResult updateEmail(Request request, ProfileEmailDto dto) {
        return withProfile(request, (userId, profile) -> {
            String email = dto == null ? null : StringUtils.trimToNull(dto.email());
            if (email == null || !EMAIL.matcher(email.toLowerCase(Locale.ROOT)).matches()) {
                return AdminSettingsResult.badRequest("A valid email address is required");
            }

            Optional<String> token;
            try {
                token = systemUserService.setEmail(userId, email);
            } catch (IllegalArgumentException e) {
                return AdminSettingsResult.badRequest(e.getMessage());
            }

            return AdminSettingsResult.ok(afterVerificationRequest(request, userId, token));
        });
    }

    public AdminSettingsResult resendEmailVerification(Request request) {
        return withProfile(request, (userId, profile) -> {
            if (StringUtils.isBlank(profile.email())) {
                return AdminSettingsResult.badRequest("No email address on this account");
            }
            if (profile.emailVerified()) {
                return AdminSettingsResult.badRequest("This address is already confirmed");
            }

            return AdminSettingsResult.ok(afterVerificationRequest(
                    request, userId, systemUserService.renewEmailVerificationToken(userId)));
        });
    }

    public AdminSettingsResult deleteEmail(Request request) {
        return withProfile(request, (userId, profile) -> {
            systemUserService.clearEmail(userId);
            return AdminSettingsResult.ok(reload(userId));
        });
    }

    // Deliberately reachable without a session: the token is the credential.
    public AdminSettingsResult confirmEmail(VerifyEmailDto dto) {
        String token = dto == null ? null : StringUtils.trimToNull(dto.token());

        return systemUserService.confirmEmailVerification(token)
                .map(profile -> AdminSettingsResult.ok(Map.of(
                        "success", true,
                        "username", StringUtils.defaultString(profile.username()))))
                .orElseGet(() -> AdminSettingsResult.badRequest("This confirmation link is invalid or has expired"));
    }

    public AdminSettingsResult updateLoginAlert(Request request, LoginAlertDto dto) {
        return withProfile(request, (userId, profile) -> {
            if (dto == null || dto.enabled() == null) {
                return AdminSettingsResult.badRequest("Enabled is required");
            }

            if (dto.enabled()) {
                if (!profile.emailVerified()) {
                    return AdminSettingsResult.badRequest("Confirm your email address first");
                }
                if (StringUtils.isBlank(config.getSmtpHost())) {
                    return AdminSettingsResult.badRequest("SMTP is not configured on this instance");
                }

                // Trust the current device, or the next sign-in would alert about this very session.
                loginAlertService.trustCurrentOrigin(userId, request);
            }

            systemUserService.setLoginAlertEnabled(userId, dto.enabled());

            return AdminSettingsResult.ok(reload(userId));
        });
    }

    public AdminSettingsResult updateAvatar(Request request, UpdateAvatarDto dto) {
        return withProfile(request, (userId, profile) -> {
            Matcher matcher = DATA_URL.matcher(dto == null ? "" : StringUtils.defaultString(dto.image()).trim());
            if (!matcher.matches()) {
                return AdminSettingsResult.badRequest("Expected a base64 image data URL");
            }

            String declaredType = matcher.group(1).toLowerCase(Locale.ROOT);
            if (!AVATAR_MIME_TYPES.contains(declaredType)) {
                return AdminSettingsResult.badRequest("Only PNG, JPEG and WebP images are supported");
            }

            byte[] data;
            try {
                data = Base64.getDecoder().decode(matcher.group(2).replaceAll("\\s", ""));
            } catch (IllegalArgumentException e) {
                return AdminSettingsResult.badRequest("The image could not be decoded");
            }

            if (data.length == 0) {
                return AdminSettingsResult.badRequest("The image is empty");
            }
            if (data.length > MAX_AVATAR_BYTES) {
                return AdminSettingsResult.badRequest("The image must be smaller than " + (MAX_AVATAR_BYTES / 1024) + " KB");
            }

            // The bytes must match: an SVG or HTML announced as PNG could otherwise be rendered by a browser.
            String detected = MimeTypes.detect(data, null);
            if (!AVATAR_MIME_TYPES.contains(StringUtils.lowerCase(detected))) {
                return AdminSettingsResult.badRequest("The uploaded file is not a PNG, JPEG or WebP image");
            }

            systemUserService.setAvatar(userId, data, detected);

            return AdminSettingsResult.ok(reload(userId));
        });
    }

    public AdminSettingsResult deleteAvatar(Request request) {
        return withProfile(request, (userId, profile) -> {
            systemUserService.clearAvatar(userId);
            return AdminSettingsResult.ok(reload(userId));
        });
    }

    public Optional<SystemUserService.Avatar> readAvatar(Request request) {
        return authService.resolveAdmin(request)
                .map(AuthContext::id)
                .flatMap(systemUserService::findAvatar);
    }

    public AdminSettingsResult changePassword(Request request, ChangePasswordDto dto) {
        return withProfile(request, (userId, profile) -> {
            if (dto == null || dto.currentPassword() == null || dto.newPassword() == null) {
                return AdminSettingsResult.badRequest("Current password and new password are required");
            }

            SuperadminPasswordResult verified =
                    systemUserService.verifyPassword(profile.username(), dto.currentPassword());
            if (verified.isAtCapacity()) {
                return AdminSettingsResult.atCapacity();
            }
            if (verified.auth().isEmpty()) {
                return AdminSettingsResult.unauthorized("Invalid current password");
            }

            if (dto.currentPassword().equals(dto.newPassword())) {
                return AdminSettingsResult.badRequest("New password must be different from the current password");
            }

            try {
                systemUserService.changePassword(userId, dto.newPassword());
            } catch (IllegalArgumentException e) {
                return AdminSettingsResult.badRequest(e.getMessage());
            } catch (MangooHashingException e) {
                // Nothing was written, so the old password still stands.
                return AdminSettingsResult.atCapacity();
            }

            // No token issued under the old password may outlive a change.
            revokeOtherSessions(request, userId);
            return AdminSettingsResult.ok(Map.of("success", true));
        });
    }

    public AdminSettingsResult setupTwoFactor(Request request, TwoFactorSetupDto dto) {
        return withProfile(request, (userId, profile) -> {
            SuperadminPasswordResult verified = systemUserService.verifyPassword(profile.username(), dto.password());
            if (verified.isAtCapacity()) {
                return AdminSettingsResult.atCapacity();
            }
            if (verified.auth().isEmpty()) {
                return AdminSettingsResult.unauthorized("Invalid password");
            }

            String secret = twoFactorService.generateSecret();
            PendingTwoFactorSession.setPendingSetup(request, secret);

            return AdminSettingsResult.ok(Map.of(
                    "secret", secret,
                    "uri", twoFactorService.buildUri(profile.username(), secret)
            ));
        });
    }

    public AdminSettingsResult confirmTwoFactor(Request request, TwoFactorCodeDto dto) {
        return withProfile(request, (userId, profile) -> {
            Optional<String> pendingSecret = PendingTwoFactorSession.getPendingSecret(request);
            if (pendingSecret.isEmpty()) {
                return AdminSettingsResult.badRequest("No pending 2FA setup");
            }

            if (!twoFactorService.verifyCode(pendingSecret.orElseThrow(), dto.code())) {
                return AdminSettingsResult.badRequest("Invalid verification code");
            }

            systemUserService.setTotpSecret(userId, pendingSecret.orElseThrow());
            String fallbackCode = systemUserService.generateTotpFallbackCode(userId);
            PendingTwoFactorSession.clear(request);
            // A token issued before 2FA was on never passed the second factor
            revokeOtherSessions(request, userId);

            return AdminSettingsResult.ok(Map.of(
                    "twoFactorEnabled", true,
                    "fallbackCode", fallbackCode
            ));
        });
    }

    public AdminSettingsResult disableTwoFactor(Request request, TwoFactorSetupDto dto) {
        return withProfile(request, (userId, profile) -> {
            SuperadminPasswordResult verified = systemUserService.verifyPassword(profile.username(), dto.password());
            if (verified.isAtCapacity()) {
                return AdminSettingsResult.atCapacity();
            }
            if (verified.auth().isEmpty()) {
                return AdminSettingsResult.unauthorized("Invalid password");
            }

            Optional<String> secret = systemUserService.findTotpSecret(userId);
            boolean codeProvided = StringUtils.isNotBlank(dto.code());

            if (secret.isPresent() && !codeProvided) {
                return AdminSettingsResult.badRequest("Verification code is required");
            }

            if (codeProvided && secret.isPresent() && !twoFactorService.verifyCode(secret.orElseThrow(), dto.code())) {
                return AdminSettingsResult.badRequest("Invalid verification code");
            }

            systemUserService.clearTotpSecret(userId);
            PendingTwoFactorSession.clear(request);
            revokeOtherSessions(request, userId);

            return AdminSettingsResult.ok(Map.of("twoFactorEnabled", false));
        });
    }

    // Revokes every session of the account, then reissues this one's cookie: issued after the
    // revocation, it stays valid and keeps the lifetime counted from sign-in.
    private void revokeOtherSessions(Request request, String userId) {
        tokenVersionService.revokeAllOfSuperadmin(userId);
        request.getAuthentication().update();
    }

    @FunctionalInterface
    private interface ProfileAction {
        AdminSettingsResult apply(String userId, SuperadminProfile profile);
    }

    private AdminSettingsResult withProfile(Request request, ProfileAction action) {
        Optional<AuthContext> auth = authService.resolveAdmin(request);
        if (auth.isEmpty()) {
            return AdminSettingsResult.unauthorized("Unauthorized");
        }

        String userId = auth.orElseThrow().id();

        return systemUserService.findProfile(userId)
                .map(profile -> action.apply(userId, profile))
                .orElseGet(() -> AdminSettingsResult.notFound("User not found"));
    }

    private Map<String, Object> afterVerificationRequest(Request request, String userId, Optional<String> token) {
        SuperadminProfile profile = systemUserService.findProfile(userId).orElseThrow();
        Map<String, Object> payload = toPayload(profile);

        boolean smtpConfigured = StringUtils.isNotBlank(config.getSmtpHost());
        String link = token
                .map(value -> InstanceLinks.absolute(
                        request.getHeader(Headers.HOST),
                        request.getHeader(Headers.X_FORWARDED_PROTO),
                        "/verify-email#token=" + value))
                .orElse(null);

        boolean sent = smtpConfigured && link != null && StringUtils.isNotBlank(profile.email());
        if (sent) {
            mailService.sendSuperadminEmailVerification(profile.email(), link, profile.username());
        }

        payload.put("verificationEmailSent", sent);

        return payload;
    }

    private Map<String, Object> reload(String userId) {
        return systemUserService.findProfile(userId).map(this::toPayload).orElseGet(LinkedHashMap::new);
    }

    private Map<String, Object> toPayload(SuperadminProfile profile) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", profile.username());
        payload.put("email", profile.email());
        payload.put("emailVerified", profile.emailVerified());
        payload.put("emailVerificationPending", profile.emailVerificationPending());
        payload.put("loginAlertEnabled", profile.loginAlertEnabled());
        payload.put("twoFactorEnabled", profile.twoFactorEnabled());
        payload.put("smtpConfigured", StringUtils.isNotBlank(config.getSmtpHost()));
        payload.put("avatarUrl", avatarUrl(profile));

        return payload;
    }

    static String avatarUrl(SuperadminProfile profile) {
        return profile == null || profile.avatarVersion() == null
                ? null
                : "/api/admin/profile/avatar?v=" + profile.avatarVersion();
    }
}
