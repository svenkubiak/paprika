package controllers;

import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestResponse;
import io.mangoo.utils.JsonUtils;
import io.undertow.util.StatusCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.JsonNode;
import utils.AdminTestUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@ExtendWith({TestRunner.class})
class AdminSettingsValidationTest {

    @Test
    void aSettingsChangeIsRefusedWhenItCannotBeStored() {
        assertThat(patch("{}").getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(patch("{}").getContent(), containsString("No settings to update"));

        TestResponse ip = patch("{\"requestLogClientIp\":\"raw\"}");
        assertThat(ip.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(ip.getContent(), containsString("Client IP mode"));

        TestResponse negative = patch("{\"requestLogRetentionDays\":-1}");
        assertThat(negative.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(negative.getContent(), containsString("Retention days"));

        TestResponse huge = patch("{\"requestLogRetentionDays\":3651}");
        assertThat(huge.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(huge.getContent(), containsString("Retention days"));

        TestResponse tenant = patch("{\"defaultTenantId\":\"no-such-tenant\"}");
        assertThat(tenant.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(tenant.getContent(), containsString("Tenant not found"));
    }

    @Test
    void aValidChangeIsReturnedAndCanBePutBack() {
        JsonNode before = JsonUtils.getMapper().readTree(get().getContent());
        int days = before.get("requestLogRetentionDays").asInt();
        String ip = before.get("requestLogClientIp").asString();
        int changedDays = days == 30 ? 31 : 30;
        String changedIp = "off".equals(ip) ? "truncated" : "off";

        try {
            TestResponse updated = patch(
                    "{\"requestLogRetentionDays\":" + changedDays
                            + ",\"requestLogClientIp\":\"" + changedIp + "\"}");
            assertThat(updated.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(updated.getContent(), containsString("\"requestLogRetentionDays\":" + changedDays));
            assertThat(updated.getContent(), containsString("\"requestLogClientIp\":\"" + changedIp + "\""));
        } finally {
            TestResponse restored = patch(
                    "{\"requestLogRetentionDays\":" + days
                            + ",\"requestLogClientIp\":\"" + ip + "\"}");
            assertThat(restored.getStatusCode(), equalTo(StatusCodes.OK));
        }
    }

    private static TestResponse get() {
        return AdminTestUtils.getWithAdminCookies(
                "/api/admin/settings", AdminTestUtils.loginAsAdminWithDefaultTenant());
    }

    private static TestResponse patch(String body) {
        return AdminTestUtils.patchWithAdminCookies(
                "/api/admin/settings", AdminTestUtils.loginAsAdminWithDefaultTenant(), body, "application/json");
    }
}
