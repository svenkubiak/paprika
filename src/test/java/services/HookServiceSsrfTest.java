package services;

import auth.TenantContext;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import models.HookDefinition;
import models.HookEvent;
import models.TenantDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith({TestRunner.class})
class HookServiceSsrfTest {

    @Test
    void publicHostIsAllowedWithoutAllowlistEntry() {
        TenantContext ctx = tenantWithAllowlist("ssrf-public-test", List.of());

        assertDoesNotThrow(() -> validate(ctx, "https://203.0.113.10/hook"));
    }

    @Test
    void loopbackHostIsAllowedWhenAllowlisted() {
        TenantContext ctx = tenantWithAllowlist("ssrf-loopback-allowed-test", List.of("127.0.0.1:8092"));

        assertDoesNotThrow(() -> validate(ctx, "http://127.0.0.1:8092/hook"));
    }

    @Test
    void loopbackHostIsBlockedWhenNotAllowlisted() {
        TenantContext ctx = tenantWithAllowlist("ssrf-loopback-blocked-test", List.of());

        assertThrows(IllegalArgumentException.class, () -> validate(ctx, "http://127.0.0.1:8092/hook"));
    }

    @Test
    void privateHostIsAllowedWhenAllowlisted() {
        TenantContext ctx = tenantWithAllowlist("ssrf-private-allowed-test", List.of("192.168.1.10:9000"));

        assertDoesNotThrow(() -> validate(ctx, "http://192.168.1.10:9000/hook"));
    }

    @Test
    void privateHostIsBlockedWhenNotAllowlisted() {
        TenantContext ctx = tenantWithAllowlist("ssrf-private-blocked-test", List.of());

        assertThrows(IllegalArgumentException.class, () -> validate(ctx, "http://10.0.0.5:80/hook"));
    }

    @Test
    void localhostIsBlockedWhenNotAllowlisted() {
        TenantContext ctx = tenantWithAllowlist("ssrf-localhost-blocked-test", List.of());

        assertThrows(IllegalArgumentException.class, () -> validate(ctx, "http://localhost:8092/hook"));
    }

    @Test
    void ipv6UniqueLocalAddressIsBlockedWhenNotAllowlisted() {
        TenantContext ctx = tenantWithAllowlist("ssrf-ipv6-ula-blocked-test", List.of());

        assertThrows(IllegalArgumentException.class, () -> validate(ctx, "http://[fd12:3456:789a::1]:8092/hook"));
    }

    private static void validate(TenantContext ctx, String url) {
        HookService hookService = Application.getInstance(HookService.class);
        HookDefinition hook = new HookDefinition(
                "test",
                "SSRF test hook",
                "hooks_ssrf_test",
                HookEvent.afterCreate,
                url,
                "POST",
                null,
                "test-signing-secret",
                null,
                true,
                50,
                null,
                null,
                null,
                null);
        hookService.validate(hook, ctx);
    }

    private static TenantContext tenantWithAllowlist(String slug, List<String> allowlist) {
        TenantService tenantService = Application.getInstance(TenantService.class);
        TenantDefinition tenant = tenantService.findBySlug(slug)
                .orElseGet(() -> tenantService.create("SSRF Test " + slug, slug));
        tenantService.update(tenant.id(), null, null, null, null, null, null, null, null, null, allowlist);
        return TenantContext.guest(tenant.id(), tenant.databaseName());
    }
}
