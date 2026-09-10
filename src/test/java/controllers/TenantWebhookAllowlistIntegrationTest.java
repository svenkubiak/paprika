package controllers;

import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.undertow.util.StatusCodes;
import models.TenantDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantService;
import utils.AdminTestUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@ExtendWith({TestRunner.class})
class TenantWebhookAllowlistIntegrationTest {

    @Test
    void readReturnsEmptyAllowlistForFreshTenant() {
        TenantDefinition tenant = Application.getInstance(TenantService.class)
                .create("Webhook Allowlist Read Test", "webhook-allowlist-read-test");
        AdminTestUtils.AdminCookies cookies = new AdminTestUtils.AdminCookies(AdminTestUtils.loginAsAdmin(), null);

        var response = AdminTestUtils.getWithAdminCookies("/api/meta/tenants/" + tenant.id(), cookies);

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), containsString("\"webhookAllowlist\":[]"));
    }

    @Test
    void updateNormalizesAndPersistsAllowlist() {
        TenantDefinition tenant = Application.getInstance(TenantService.class)
                .create("Webhook Allowlist Update Test", "webhook-allowlist-update-test");
        AdminTestUtils.AdminCookies cookies = new AdminTestUtils.AdminCookies(AdminTestUtils.loginAsAdmin(), null);

        var update = AdminTestUtils.patchWithAdminCookies(
                "/api/meta/tenants/" + tenant.id(),
                cookies,
                "{\"webhookAllowlist\":[\" 127.0.0.1:8092 \", \"127.0.0.1:8092\", \"\", \"192.168.1.10:9000\"]}",
                "application/json");

        assertThat(update.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(update.getContent(), containsString("\"127.0.0.1:8092\""));
        assertThat(update.getContent(), containsString("\"192.168.1.10:9000\""));

        var read = AdminTestUtils.getWithAdminCookies("/api/meta/tenants/" + tenant.id(), cookies);
        assertThat(read.getContent(), not(containsString("\" 127.0.0.1:8092 \"")));
        long occurrences = read.getContent().split("127\\.0\\.0\\.1:8092", -1).length - 1;
        assertThat(occurrences, equalTo(1L));
    }
}
