package services;

import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import models.TenantDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Email addresses are stored in one canonical shape.
 * <p>
 * A mail domain is case insensitive and providers treat the local part that way too, so two
 * spellings of the same address must never become two identities. Storing them lowercased keeps
 * every lookup a plain equality match - the alternative, comparing case insensitively at every call
 * site, is the kind of rule that holds until someone adds the next query.
 */
@ExtendWith({TestRunner.class})
class EmailNormalizationTest {

    @Test
    void tenantUserAddressesAreStoredLowercased() {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        String suffix = DbUtils.id().substring(0, 8);

        Map<String, Object> user = Application.getInstance(TenantUserService.class)
                .createUser(tenant, "norm-user-" + suffix, "  Mixed.Case+" + suffix + "@Example.TEST  ",
                        "normalization-password-1");

        assertThat(user.get("email"), equalTo("mixed.case+" + suffix + "@example.test"));
    }

    @Test
    void tenantUserAddressesStayNormalizedOnUpdate() {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        String suffix = DbUtils.id().substring(0, 8);
        TenantUserService users = Application.getInstance(TenantUserService.class);

        String userId = String.valueOf(users
                .createUser(tenant, "norm-update-" + suffix, "first-" + suffix + "@example.test",
                        "normalization-password-1")
                .get("id"));

        Map<String, Object> updated = users
                .updateUser(tenant, userId, null, "SECOND-" + suffix + "@Example.TEST", null)
                .orElseThrow();

        assertThat(updated.get("email"), equalTo("second-" + suffix + "@example.test"));
    }

    @Test
    void superadminAddressesFollowTheSameRule() {
        String suffix = DbUtils.id().substring(0, 8);

        Application.getInstance(SystemUserService.class)
                .createSuperadminSetup("norm-admin-" + suffix, "  Admin." + suffix + "@Example.TEST ");

        Map<String, Object> invite = Application.getInstance(SystemUserService.class)
                .findPublicUserByUsername("norm-admin-" + suffix)
                .orElseThrow();

        assertThat(invite.get("email"), equalTo("admin." + suffix + "@example.test"));
    }

    @Test
    void aBlankAddressStaysAbsentRatherThanBecomingAnEmptyString() {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        String suffix = DbUtils.id().substring(0, 8);

        Map<String, Object> user = Application.getInstance(TenantUserService.class)
                .createUser(tenant, "norm-blank-" + suffix, "   ", "normalization-password-1");

        assertThat("a blank address must not become an empty string others could match on",
                user.get("email"), nullValue());
    }
}
