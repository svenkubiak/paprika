package dtos;

public record UpdateAdminSettingsDto(
        Integer requestLogRetentionDays,
        String defaultTenantId,
        Boolean requestLogClientInfo,
        String requestLogClientIp) {
}
