package app;

import controllers.*;
import io.mangoo.constants.Header;
import io.mangoo.core.Server;
import io.mangoo.interfaces.MangooBootstrap;
import io.mangoo.routing.Bind;
import io.mangoo.routing.On;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import services.RequestLogService;
import services.SystemCollectionService;

@Singleton
public class Bootstrap implements MangooBootstrap {
    private final SystemCollectionService systemCollectionService;
    private final RequestLogService requestLogService;

    @Inject
    public Bootstrap(SystemCollectionService systemCollectionService, RequestLogService requestLogService) {
        this.systemCollectionService = systemCollectionService;
        this.requestLogService = requestLogService;
    }
    
    @Override
    public void initializeRoutes() {
        Bind.controller(AdminController.class).withRoutes(
                On.get().to("/login").respondeWith("admin"),
                On.get().to("/setup").respondeWith("admin"),
                On.post().to("/authenticate").respondeWith("authenticate"),
                On.post().to("/api/admin/login").respondeWith("loginJson"),
                On.post().to("/api/admin/setup").respondeWith("completeSetup"),
                On.post().to("/api/admin/login/2fa").respondeWith("loginTwoFactor"),
                On.post().to("/api/admin/token").respondeWith("token"),
                On.post().to("/api/admin/token/2fa").respondeWith("tokenTwoFactor"),
                On.post().to("/api/admin/switch-tenant").respondeWith("switchTenantJwt"),
                On.post().to("/logout").respondeWith("logout")
        );

        Bind.controller(AdminController.class).withAuthentication().withRoutes(
                On.get().to("/").respondeWith("admin"),
                On.get().to("/admin/bootstrap").respondeWith("bootstrap"),
                On.post().to("/admin/switch-tenant").respondeWith("switchTenant"),
                On.get().to("/admin/tenants").respondeWith("admin"),
                On.get().to("/admin/settings").respondeWith("admin"),
                On.get().to("/admin/global-hooks").respondeWith("admin"),
                On.get().to("/admin/tenant-settings").respondeWith("admin"),
                On.get().to("/admin/logs").respondeWith("admin"),
                On.get().to("/admin/users").respondeWith("admin"),
                On.get().to("/admin/collections/{collection}/data").respondeWith("admin"),
                On.get().to("/admin/collections/{collection}/schema").respondeWith("admin"),
                On.get().to("/admin/collections/{collection}/rules").respondeWith("admin"),
                On.get().to("/admin/collections/{collection}/hooks").respondeWith("admin"),
                On.get().to("/admin/collections/{collection}/api").respondeWith("admin")
        );

        Bind.controller(AuthController.class).withRoutes(
                On.post().to("/api/auth/register").respondeWith("register"),
                On.post().to("/api/auth/login").respondeWith("login"),
                On.post().to("/api/auth/refresh").respondeWith("refresh"),
                On.get().to("/api/auth/me").respondeWith("me"),
                On.post().to("/api/auth/password/forgot").respondeWith("forgotPassword"),
                On.post().to("/api/auth/password/reset").respondeWith("resetPassword"),
                On.post().to("/api/auth/verify/request").respondeWith("requestVerification"),
                On.post().to("/api/auth/verify/confirm").respondeWith("confirmVerification")
        );

        Bind.controller(CollectionController.class).withRoutes(
                On.get().to("/api/collections/{collection}").respondeWith("list"),
                On.post().to("/api/collections/{collection}").respondeWith("create"),
                On.get().to("/api/collections/{collection}/{id}").respondeWith("read"),
                On.patch().to("/api/collections/{collection}/{id}").respondeWith("update"),
                On.delete().to("/api/collections/{collection}/{id}").respondeWith("delete")
        );

        Bind.controller(CollectionFileController.class).withRoutes(
                On.get().to("/api/collections/{collection}/{id}/files/{field}").respondeWith("download"),
                On.get().to("/api/collections/{collection}/{id}/files/{field}/{fileId}").respondeWith("downloadWithId"),
                On.delete().to("/api/collections/{collection}/{id}/files/{field}").respondeWith("deleteField"),
                On.delete().to("/api/collections/{collection}/{id}/files/{field}/{fileId}").respondeWith("deleteFileById")
        );

        Bind.controller(MetaHooksController.class).withRoutes(
                On.get().to("/api/meta/collections/{collection}/hooks").respondeWith("list"),
                On.post().to("/api/meta/collections/{collection}/hooks").respondeWith("create"),
                On.post().to("/api/meta/collections/{collection}/hooks/test").respondeWith("test"),
                On.patch().to("/api/meta/collections/{collection}/hooks/{id}").respondeWith("update"),
                On.delete().to("/api/meta/collections/{collection}/hooks/{id}").respondeWith("delete")
        );

        Bind.controller(MetaGlobalHooksController.class).withRoutes(
                On.get().to("/api/meta/global-hooks").respondeWith("list"),
                On.post().to("/api/meta/global-hooks").respondeWith("create"),
                On.post().to("/api/meta/global-hooks/test").respondeWith("test"),
                On.patch().to("/api/meta/global-hooks/{id}").respondeWith("update"),
                On.delete().to("/api/meta/global-hooks/{id}").respondeWith("delete")
        );

        Bind.controller(MetaController.class).withRoutes(
                On.get().to("/api/meta/collections/{collection}").respondeWith("read"),
                On.post().to("/api/meta/collections/{collection}").respondeWith("create"),
                On.get().to("/api/meta/collections/{collection}/{id}").respondeWith("read"),
                On.patch().to("/api/meta/collections/{collection}/{id}").respondeWith("update"),
                On.delete().to("/api/meta/collections/{collection}/{id}").respondeWith("delete")
        );

        Bind.controller(TenantController.class).withRoutes(
                On.get().to("/api/meta/tenants").respondeWith("list"),
                On.post().to("/api/meta/tenants").respondeWith("create"),
                On.get().to("/api/meta/tenants/{tenantId}").respondeWith("read"),
                On.patch().to("/api/meta/tenants/{tenantId}").respondeWith("update"),
                On.delete().to("/api/meta/tenants/{tenantId}").respondeWith("delete"),
                On.post().to("/api/meta/tenants/{tenantId}/users").respondeWith("createUser"),
                On.get().to("/api/meta/tenants/{tenantId}/users").respondeWith("listUsers"),
                On.patch().to("/api/meta/tenants/{tenantId}/users/{userId}").respondeWith("updateUser"),
                On.delete().to("/api/meta/tenants/{tenantId}/users/{userId}").respondeWith("deleteUser")
        );

        Bind.controller(AdminSettingsController.class).withRoutes(
                On.get().to("/api/admin/settings").respondeWith("list"),
                On.patch().to("/api/admin/settings").respondeWith("update"),
                On.post().to("/api/admin/settings/password").respondeWith("changePassword"),
                On.post().to("/api/admin/settings/2fa/setup").respondeWith("setupTwoFactor"),
                On.post().to("/api/admin/settings/2fa/confirm").respondeWith("confirmTwoFactor"),
                On.post().to("/api/admin/settings/2fa/disable").respondeWith("disableTwoFactor")
        );

        Bind.controller(SuperadminController.class).withRoutes(
                On.get().to("/api/admin/superadmins").respondeWith("list"),
                On.post().to("/api/admin/superadmins").respondeWith("invite"),
                On.post().to("/api/admin/superadmins/invite/email").respondeWith("emailInvite"),
                On.delete().to("/api/admin/superadmins/{id}").respondeWith("delete")
        );

        Bind.controller(RequestLogsController.class).withRoutes(
                On.get().to("/api/admin/request-logs").respondeWith("list")
        );

        Bind.controller(MetaSchemaController.class).withRoutes(
                On.get().to("/api/meta/schema/export").respondeWith("export"),
                On.post().to("/api/meta/schema/import").respondeWith("importSchema")
        );

        Bind.controller(BackupController.class).withRoutes(
                On.get().to("/api/admin/backup/export").respondeWith("export"),
                On.post().to("/api/admin/backup/import").respondeWith("importBackup")
        );

        Bind.serverSentEvent().to("/api/realtime");

        Bind.controller(RealtimeController.class).withRoutes(
                On.post().to("/api/realtime/subscribe").respondeWith("subscribe")
        );

        Bind.controller(HealthController.class).withRoutes(
                On.get().to("/health").respondeWith("health")
        );

        Bind.pathResource().to("/assets/");
        Bind.fileResource().to("/robots.txt");
    }
    
    @Override
    public void applicationInitialized() {
        Server.header(Header.CONTENT_SECURITY_POLICY,
                "default-src 'self'; " +
                "script-src 'self'; " +
                "style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data:; " +
                "font-src 'self'; " +
                "connect-src 'self'; " +
                "object-src 'none'; " +
                "base-uri 'self'; " +
                "frame-ancestors 'none'");
    }

    @Override
    public void applicationStarted() {
        systemCollectionService.ensureSystemCollections();
        requestLogService.purgeAllExpired();
    }

    @Override
    public void applicationStopped() {
        // TODO Auto-generated method stub
    }
}
