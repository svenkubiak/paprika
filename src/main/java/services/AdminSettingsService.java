package services;

import constants.SettingKeys;
import dtos.UpdateAdminSettingsDto;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.License;
import models.TenantDefinition;
import org.apache.commons.lang3.StringUtils;
import results.AdminSettingsResult;
import utils.Licenses;

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
    private final LicenseService licenseService;

    @Inject
    public AdminSettingsService(
            SettingsService settingsService,
            RequestLogService requestLogService,
            TenantService tenantService,
            LicenseService licenseService) {
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService must not be null");
        this.requestLogService = Objects.requireNonNull(requestLogService, "requestLogService must not be null");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
        this.licenseService = Objects.requireNonNull(licenseService, "licenseService must not be null");
    }

    public AdminSettingsResult readSettings() {
        Map<String, Object> payload = new LinkedHashMap<>(settingsService.getAll());
        payload.remove(SettingKeys.LICENSE_KEY);
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
        payload.put("license", licenseService.current().toPayload());

        return AdminSettingsResult.ok(payload);
    }

    public AdminSettingsResult update(UpdateAdminSettingsDto dto) {
        if (dto == null
                || (dto.requestLogRetentionDays() == null
                && dto.defaultTenantId() == null
                && dto.requestLogClientInfo() == null
                && dto.requestLogClientIp() == null
                && dto.requestLogAdminUi() == null
                && dto.licenseKey() == null)) {
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

        // Only a key that checks out is stored: a broken paste is reported now, not found later on the page
        String licenseKey = Licenses.normalize(dto.licenseKey());
        if (StringUtils.isNotEmpty(licenseKey)) {
            License license = licenseService.check(licenseKey);
            if (license.status() == License.Status.EXPIRED) {
                return AdminSettingsResult.badRequest("License expired on " + license.expiresAt());
            }
            if (license.status() != License.Status.VALID) {
                return AdminSettingsResult.badRequest("License key is invalid");
            }
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

        // An empty key removes the license
        if (licenseKey != null) {
            settingsService.set(SettingKeys.LICENSE_KEY, licenseKey);
        }

        return readSettings();
    }
}
