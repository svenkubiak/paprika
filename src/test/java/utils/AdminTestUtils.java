package utils;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import io.mangoo.core.Application;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.TenantDefinition;
import services.SystemUserService;
import services.TenantService;

import java.net.HttpCookie;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;

public final class AdminTestUtils {
    private static final String TEST_ADMIN_PASSWORD = "admin-password-123";

    public record AdminCookies(HttpCookie authentication, HttpCookie session) {
        public TestResponse apply(TestResponse request) {
            TestResponse withAuth = request.withCookie(authentication);
            if (session != null) {
                return withAuth.withCookie(session);
            }
            return withAuth;
        }
    }

    private AdminTestUtils() {
    }

    public static AdminCookies loginAsAdminWithDefaultTenant() {
        HttpCookie authentication = loginAsAdmin();
        TenantDefinition tenant = Application.getInstance(TenantService.class)
                .findBySlug("default")
                .orElseThrow(() -> new IllegalStateException("Default tenant not initialized"));

        Multimap<String, String> form = ArrayListMultimap.create();
        form.put("tenantId", tenant.id());
        form.put("redirect", "/");

        TestResponse switchTenant = TestRequest.post("/admin/switch-tenant")
                .withCookie(authentication)
                .withForm(form)
                .execute();

        assertThat(switchTenant.getStatusCode(), anyOf(equalTo(StatusCodes.FOUND), equalTo(StatusCodes.OK)));

        HttpCookie session = switchTenant.getCookie("paprika-session");

        return new AdminCookies(authentication, session);
    }

    public static HttpCookie loginAsAdmin() {
        prepareAdminPassword();

        Multimap<String, String> form = ArrayListMultimap.create();
        form.put("username", "admin");
        form.put("password", TEST_ADMIN_PASSWORD);

        TestResponse login = TestRequest.post("/authenticate")
                .withForm(form)
                .execute();

        assertThat(login.getStatusCode(), anyOf(equalTo(StatusCodes.FOUND), equalTo(StatusCodes.OK)));

        HttpCookie cookie = login.getCookie("paprika-authentication");
        if (cookie == null) {
            throw new IllegalStateException("Missing paprika-authentication cookie after admin login");
        }
        return cookie;
    }

    public static String prepareAdminPassword() {
        SystemUserService systemUserService = Application.getInstance(SystemUserService.class);
        String userId = String.valueOf(systemUserService.findPublicUserByUsername("admin")
                .orElseThrow(() -> new IllegalStateException("Superadmin not initialized"))
                .get("id"));
        systemUserService.changePassword(userId, TEST_ADMIN_PASSWORD);
        return TEST_ADMIN_PASSWORD;
    }

    public static TestResponse getWithAdminCookies(String path, AdminCookies cookies) {
        return cookies.apply(TestRequest.get(path)).execute();
    }

    public static TestResponse postWithAdminCookies(String path, AdminCookies cookies, String body, String contentType) {
        return cookies.apply(TestRequest.post(path)
                .withStringBody(body)
                .withContentType(contentType))
                .execute();
    }

    public static TestResponse patchWithAdminCookies(String path, AdminCookies cookies, String body, String contentType) {
        return cookies.apply(TestRequest.patch(path)
                .withStringBody(body)
                .withContentType(contentType))
                .execute();
    }

    public static TestResponse deleteWithAdminCookies(String path, AdminCookies cookies) {
        return cookies.apply(TestRequest.delete(path)).execute();
    }
}
