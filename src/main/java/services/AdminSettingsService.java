package services;

import constants.SettingKeys;
import dtos.UpdateAdminSettingsDto;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.TenantDefinition;
import org.apache.commons.lang3.StringUtils;
import results.AdminSettingsResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Singleton
public class AdminSettingsService {
    private static final int MAX_RETENTION_DAYS = 3650;
    private static final Set<String> CLIENT_IP_MODES = Set.of(
            SettingKeys.CLIENT_IP_OFF, SettingKeys.CLIENT_IP_TRUNCATED, SettingKeys.CLIENT_IP_FULL);

    private final SettingsService settingsService;
    private final RequestLogService requestLogService;
    private final TenantService tenantService;

    @Inject
    public AdminSettingsService(
            SettingsService settingsService,
            RequestLogService requestLogService,
            TenantService tenantService) {
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService must not be null");
        this.requestLogService = Objects.requireNonNull(requestLogService, "requestLogService must not be null");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
    }

    public AdminSettingsResult readSettings() {
        Map<String, Object> payload = new LinkedHashMap<>(settingsService.getAll());
        payload.put(
                "requestLogRetentionDays",
                settingsService.getInt(
                        SettingKeys.REQUEST_LOG_RETENTION_DAYS,
                        SettingKeys.DEFAULT_REQUEST_LOG_RETENTION_DAYS));
        payload.put(
                "defaultTenantId",
                StringUtils.trimToNull(settingsService.get(SettingKeys.DEFAULT_TENANT_ID, null)));
        payload.put(
                "requestLogClientInfo",
                settingsService.getBoolean(SettingKeys.REQUEST_LOG_CLIENT_INFO, false));
        payload.put(
                "requestLogClientIp",
                settingsService.get(SettingKeys.REQUEST_LOG_CLIENT_IP, SettingKeys.CLIENT_IP_OFF));
        payload.put(
                "requestLogAdminUi",
                settingsService.getBoolean(SettingKeys.REQUEST_LOG_ADMIN_UI, false));

        return AdminSettingsResult.ok(payload);
    }

    public AdminSettingsResult update(UpdateAdminSettingsDto dto) {
        if (dto == null
                || (dto.requestLogRetentionDays() == null
                && dto.defaultTenantId() == null
                && dto.requestLogClientInfo() == null
                && dto.requestLogClientIp() == null
                && dto.requestLogAdminUi() == null)) {
            return AdminSettingsResult.badRequest("No settings to update");
        }

        String clientIpMode = dto.requestLogClientIp() == null ? null : dto.requestLogClientIp().trim();
        if (clientIpMode != null && !CLIENT_IP_MODES.contains(clientIpMode)) {
            return AdminSettingsResult.badRequest("Client IP mode must be one of " + CLIENT_IP_MODES);
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

        if (dto.requestLogClientInfo() != null) {
            settingsService.set(
                    SettingKeys.REQUEST_LOG_CLIENT_INFO,
                    String.valueOf(dto.requestLogClientInfo()));
        }

        if (clientIpMode != null) {
            settingsService.set(SettingKeys.REQUEST_LOG_CLIENT_IP, clientIpMode);
        }

        if (dto.requestLogAdminUi() != null) {
            settingsService.set(
                    SettingKeys.REQUEST_LOG_ADMIN_UI,
                    String.valueOf(dto.requestLogAdminUi()));
        }

        return readSettings();
    }
}
