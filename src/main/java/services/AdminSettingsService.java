package services;

import auth.AuthContext;
import dtos.ChangePasswordDto;
import dtos.TwoFactorCodeDto;
import dtos.TwoFactorSetupDto;
import dtos.UpdateAdminSettingsDto;
import io.mangoo.routing.bindings.Request;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.TenantDefinition;
import org.apache.commons.lang3.StringUtils;
import constants.SettingKeys;
import results.AdminSettingsResult;
import session.PendingTwoFactorSession;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Singleton
public class AdminSettingsService {
    private static final int MAX_RETENTION_DAYS = 3650;

    private final SettingsService settingsService;
    private final SystemUserService systemUserService;
    private final TwoFactorService twoFactorService;
    private final AuthService authService;
    private final RequestLogService requestLogService;
    private final TenantService tenantService;

    @Inject
    public AdminSettingsService(
            SettingsService settingsService,
            SystemUserService systemUserService,
            TwoFactorService twoFactorService,
            AuthService authService,
            RequestLogService requestLogService,
            TenantService tenantService) {
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService must not be null");
        this.systemUserService = Objects.requireNonNull(systemUserService, "systemUserService must not be null");
        this.twoFactorService = Objects.requireNonNull(twoFactorService, "twoFactorService must not be null");
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.requestLogService = Objects.requireNonNull(requestLogService, "requestLogService must not be null");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
    }

    public AdminSettingsResult readSettings(Request request) {
        Map<String, Object> payload = new LinkedHashMap<>(settingsService.getAll());
        payload.put("twoFactorEnabled", isTwoFactorEnabledFor(request));
        payload.put(
                "requestLogRetentionDays",
                settingsService.getInt(
                        SettingKeys.REQUEST_LOG_RETENTION_DAYS,
                        SettingKeys.DEFAULT_REQUEST_LOG_RETENTION_DAYS));
        payload.put(
                "defaultTenantId",
                StringUtils.trimToNull(settingsService.get(SettingKeys.DEFAULT_TENANT_ID, null)));

        return AdminSettingsResult.ok(payload);
    }

    public AdminSettingsResult update(Request request, UpdateAdminSettingsDto dto) {
        if (dto == null || (dto.requestLogRetentionDays() == null && dto.defaultTenantId() == null)) {
            return AdminSettingsResult.badRequest("No settings to update");
        }

        Integer retentionDays = dto.requestLogRetentionDays();
        if (retentionDays != null && (retentionDays < 0 || retentionDays > MAX_RETENTION_DAYS)) {
            return AdminSettingsResult.badRequest("Retention days must be between 0 and " + MAX_RETENTION_DAYS);
        }

        String defaultTenantId = dto.defaultTenantId() == null ? null : dto.defaultTenantId().trim();
        if (StringUtils.isNotEmpty(defaultTenantId)
                && tenantService.findById(defaultTenantId).filter(TenantDefinition::isActive).isEmpty()) {
            return AdminSettingsResult.badRequest("Tenant not found");
        }

        if (retentionDays != null) {
            settingsService.set(SettingKeys.REQUEST_LOG_RETENTION_DAYS, String.valueOf(retentionDays));
            if (retentionDays > 0) {
                requestLogService.purgeAllExpired();
            }
        }

        if (defaultTenantId != null) {
            settingsService.set(SettingKeys.DEFAULT_TENANT_ID, defaultTenantId);
        }

        return readSettings(request);
    }

    /** Whether the superadmin making this request has a personal TOTP secret enrolled. */
    private boolean isTwoFactorEnabledFor(Request request) {
        return authService.resolveAdmin(request)
                .map(auth -> systemUserService.findTotpSecret(auth.id()).isPresent())
                .orElse(false);
    }

    public AdminSettingsResult changePassword(Request request, ChangePasswordDto dto) {
        Optional<AuthContext> auth = authService.resolveAdmin(request);
        if (auth.isEmpty()) {
            return AdminSettingsResult.unauthorized("Unauthorized");
        }

        if (dto == null || dto.currentPassword() == null || dto.newPassword() == null) {
            return AdminSettingsResult.badRequest("Current password and new password are required");
        }

        String adminId = auth.get().id();
        if (!systemUserService.matchesPassword(adminId, dto.currentPassword())) {
            return AdminSettingsResult.unauthorized("Invalid current password");
        }

        if (dto.currentPassword().equals(dto.newPassword())) {
            return AdminSettingsResult.badRequest("New password must be different from the current password");
        }

        try {
            systemUserService.changePassword(adminId, dto.newPassword());
        } catch (IllegalArgumentException e) {
            return AdminSettingsResult.badRequest(e.getMessage());
        }

        return AdminSettingsResult.ok(Map.of("success", true));
    }

    public AdminSettingsResult setupTwoFactor(Request request, TwoFactorSetupDto dto) {
        Optional<AuthContext> auth = authService.resolveAdmin(request);
        if (auth.isEmpty()) {
            return AdminSettingsResult.unauthorized("Unauthorized");
        }

        Optional<Map<String, Object>> user = systemUserService.findPublicUser(auth.get().id());
        if (user.isEmpty()) {
            return AdminSettingsResult.notFound("User not found");
        }

        String username = String.valueOf(user.get().get("username"));
        if (systemUserService.verifyPassword(username, dto.password()).isEmpty()) {
            return AdminSettingsResult.unauthorized("Invalid password");
        }

        String secret = twoFactorService.generateSecret();
        PendingTwoFactorSession.setPendingSetup(request, secret);

        return AdminSettingsResult.ok(Map.of(
                "secret", secret,
                "uri", twoFactorService.buildUri(username, secret)
        ));
    }

    public AdminSettingsResult confirmTwoFactor(Request request, TwoFactorCodeDto dto) {
        Optional<AuthContext> auth = authService.resolveAdmin(request);
        if (auth.isEmpty()) {
            return AdminSettingsResult.unauthorized("Unauthorized");
        }

        Optional<String> pendingSecret = PendingTwoFactorSession.getPendingSecret(request);
        if (pendingSecret.isEmpty()) {
            return AdminSettingsResult.badRequest("No pending 2FA setup");
        }

        if (!twoFactorService.verifyCode(pendingSecret.get(), dto.code())) {
            return AdminSettingsResult.badRequest("Invalid verification code");
        }

        systemUserService.setTotpSecret(auth.get().id(), pendingSecret.get());
        PendingTwoFactorSession.clear(request);

        return AdminSettingsResult.ok(Map.of("twoFactorEnabled", true));
    }

    public AdminSettingsResult disableTwoFactor(Request request, TwoFactorSetupDto dto) {
        Optional<AuthContext> auth = authService.resolveAdmin(request);
        if (auth.isEmpty()) {
            return AdminSettingsResult.unauthorized("Unauthorized");
        }

        String adminId = auth.get().id();
        Optional<Map<String, Object>> user = systemUserService.findPublicUser(adminId);
        if (user.isEmpty()) {
            return AdminSettingsResult.notFound("User not found");
        }

        String username = String.valueOf(user.get().get("username"));
        if (systemUserService.verifyPassword(username, dto.password()).isEmpty()) {
            return AdminSettingsResult.unauthorized("Invalid password");
        }

        Optional<String> secret = systemUserService.findTotpSecret(adminId);
        boolean codeProvided = StringUtils.isNotBlank(dto.code());

        if (secret.isPresent() && !codeProvided) {
            return AdminSettingsResult.badRequest("Verification code is required");
        }

        if (codeProvided && secret.isPresent() && !twoFactorService.verifyCode(secret.get(), dto.code())) {
            return AdminSettingsResult.badRequest("Invalid verification code");
        }

        systemUserService.clearTotpSecret(adminId);
        PendingTwoFactorSession.clear(request);

        return AdminSettingsResult.ok(Map.of("twoFactorEnabled", false));
    }
}
