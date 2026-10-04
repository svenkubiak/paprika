package services;

import auth.AuthorizationDecision;
import auth.TenantContext;
import controllers.AdminController;
import controllers.TenantController;
import dtos.CompleteSetupDto;
import dtos.UserDto;
import dtos.UserUpdateDto;
import hooks.HookRequestUtils;
import io.mangoo.core.Application;
import io.mangoo.exceptions.MangooHashingException;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import io.mangoo.test.TestRunner;
import io.undertow.util.StatusCodes;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rules.RuleOperation;
import utils.DbUtils;
import utils.TenantTestUtils;

import static com.mongodb.client.model.Filters.eq;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Hashing a new password competes for mangoo's Argon2 slots like a login does. A refusal there is
 * an overload the client may retry (429), not a server error. Provoked through the hashing seams,
 * because mangoo's fair semaphore cannot be saturated reliably from outside.
 */
@ExtendWith({TestRunner.class})
class PasswordHashRefusalTest {
    private static final String PASSWORD = "refusal-password-1234";
    private static final String NEW_PASSWORD = "refusal-new-password-5678";

    @Test
    void aDataPlaneCreateOfAUserIsAnsweredWith429() {
        String username = "refusal-create-" + DbUtils.id();
        Request request = adminRequest(RuleOperation.CREATE,
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");

        CollectionRecordService.RecordResult result =
                refusingRecords().create(TenantTestUtils.defaultTenantContext(), "users", request);

        assertThat(result.status(), is(CollectionRecordService.RecordResult.Status.TOO_MANY_REQUESTS));
        assertThat("nothing is written", users().find(eq("username", username)).first(), nullValue());
    }

    @Test
    void aDataPlanePasswordChangeIsAnsweredWith429AndKeepsTheOldPassword() {
        String username = "refusal-update-" + DbUtils.id();
        String id = String.valueOf(Application.getInstance(UserService.class).createUser(username, null, PASSWORD).get("id"));
        String hashBefore = users().find(eq("id", id)).first().getString("passwordHash");
        Request request = adminRequest(RuleOperation.UPDATE, "{\"password\":\"" + NEW_PASSWORD + "\"}");

        CollectionRecordService.RecordResult result =
                refusingRecords().update(TenantTestUtils.defaultTenantContext(), "users", id, request);

        assertThat(result.status(), is(CollectionRecordService.RecordResult.Status.TOO_MANY_REQUESTS));
        assertThat(users().find(eq("id", id)).first().getString("passwordHash"), equalTo(hashBefore));
    }

    @Test
    void theAdminEditorAnswersWith429() {
        TenantController controller = new TenantController(
                Application.getInstance(TenantService.class),
                refusingTenantUsers(),
                Application.getInstance(ApiKeyService.class));
        String tenantId = TenantTestUtils.defaultTenant().id();

        Response created = controller.createUser(
                tenantId, new UserDto("refusal-editor-" + DbUtils.id(), PASSWORD, null), new Request());
        assertRefused(created);

        String id = String.valueOf(Application.getInstance(UserService.class)
                .createUser("refusal-editor-update-" + DbUtils.id(), null, PASSWORD).get("id"));
        Response updated = controller.updateUser(
                tenantId, id, new UserUpdateDto(null, null, NEW_PASSWORD), new Request());
        assertRefused(updated);
    }

    @Test
    void completingTheSuperadminSetupAnswersWith429AndKeepsTheLinkUsable() {
        SystemUserService refusing = refusingSystemUsers();
        String username = "refusal-setup-" + DbUtils.id().substring(0, 8);
        String token = refusing.createSuperadminSetup(username, null);
        AdminController controller = new AdminController(
                Application.getInstance(AuthResponseService.class),
                Application.getInstance(AdminBootstrapService.class),
                Application.getInstance(AdminLoginService.class),
                refusing,
                Application.getInstance(SuperadminProfileService.class),
                Application.getInstance(TenantService.class),
                Application.getInstance(AuthService.class));

        try {
            assertRefused(controller.completeSetup(new CompleteSetupDto(token, username, PASSWORD), null, new Request()));

            assertThat("the token was not claimed by the refused attempt",
                    Application.getInstance(SystemUserService.class)
                            .completeSuperadminSetup(token, username, PASSWORD).isPresent(), is(true));
        } finally {
            // Other tests count the superadmins
            SystemUserService systemUsers = Application.getInstance(SystemUserService.class);
            systemUsers.findPublicUserByUsername(username)
                    .ifPresent(user -> utils.AdminTestUtils.removeSuperadmin(String.valueOf(user.get("id"))));
        }
    }

    private static void assertRefused(Response response) {
        assertThat(response.getStatusCode(), equalTo(StatusCodes.TOO_MANY_REQUESTS));
        assertThat(response.getHeader(io.undertow.util.HttpString.tryFromString("Retry-After")), equalTo("1"));
    }

    private static Request adminRequest(RuleOperation operation, String body) {
        Request request = new Request();
        AuthorizationDecision.adminBypass(operation, null).storeIn(request);
        request.addAttribute(HookRequestUtils.MUTATED_BODY_ATTRIBUTE, body);
        return request;
    }

    private static com.mongodb.client.MongoCollection<Document> users() {
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        return Application.getInstance(TenantCollectionService.class).dataCollection(ctx, "users");
    }

    private static MangooHashingException refusal() {
        return new MangooHashingException("No free Argon2 slot");
    }

    private static CollectionRecordService refusingRecords() {
        return new CollectionRecordService(
                Application.getInstance(TenantCollectionService.class),
                Application.getInstance(HookService.class),
                Application.getInstance(FileFieldService.class),
                Application.getInstance(RelationCascadeService.class),
                Application.getInstance(TokenVersionService.class)) {
            @Override
            String hashPassword(String password, String salt) {
                throw refusal();
            }
        };
    }

    private static TenantUserService refusingTenantUsers() {
        return new TenantUserService(
                Application.getInstance(TenantDatabaseResolver.class),
                Application.getInstance(TenantService.class),
                Application.getInstance(RealtimeService.class),
                Application.getInstance(TenantCollectionService.class),
                Application.getInstance(ValidationService.class),
                Application.getInstance(TokenVersionService.class)) {
            @Override
            String hashPassword(String password, String salt) {
                throw refusal();
            }
        };
    }

    private static SystemUserService refusingSystemUsers() {
        return new SystemUserService(Application.getInstance(TenantDatabaseResolver.class)) {
            @Override
            String hashPassword(String password, String salt) {
                throw refusal();
            }
        };
    }
}
