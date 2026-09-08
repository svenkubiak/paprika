package dtos;

public record UpdateAdminSettingsDto(Integer requestLogRetentionDays, String defaultTenantId) {
}
