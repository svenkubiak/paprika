package services;

import auth.AuthContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.TenantDefinition;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Backward-compatible facade for tests and legacy callers.
 * Delegates tenant user operations to the default tenant.
 */
@Singleton
public class UserService {
    private final TenantUserService tenantUserService;
    private final TenantService tenantService;

    @Inject
    public UserService(TenantUserService tenantUserService, TenantService tenantService) {
        this.tenantUserService = Objects.requireNonNull(tenantUserService, "tenantUserService must not be null");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
    }

    public Map<String, Object> createUser(String username, String email, String password) {
        return tenantUserService.createUser(requireDefaultTenant(), username, email, password);
    }

    public Optional<AuthContext> authenticate(String username, String password) {
        return tenantUserService.authenticateForLogin(username, password, null).auth();
    }

    private TenantDefinition requireDefaultTenant() {
        return tenantService.findBySlug("default")
                .orElseThrow(() -> new IllegalStateException("Default tenant not initialized"));
    }
}
