package validation;

import auth.TenantContext;

public record ValidationContext(TenantContext tenantContext) {
    public static ValidationContext empty() {
        return new ValidationContext(null);
    }

    public static ValidationContext of(TenantContext tenantContext) {
        return new ValidationContext(tenantContext);
    }
}
