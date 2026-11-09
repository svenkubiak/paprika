package services;

import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.AdminTestUtils;
import utils.DbUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Instances starting at the same time each log a setup link for the first superadmin. Whichever
 * link the operator copies has to work.
 */
@ExtendWith({TestRunner.class})
class SuperadminSetupTokenTest {
    private static final String PASSWORD = "setup-token-password-1234";

    @Test
    void aSetupCreatedByAnotherInstanceIsJoinedInsteadOfFailingTheStart() {
        SystemUserService systemUsers = Application.getInstance(SystemUserService.class);
        String username = "setup-join-" + DbUtils.id().substring(0, 8);
        systemUsers.createSuperadminSetup(username, null);

        try {
            String token = systemUsers.createInitialSetup(username);

            assertThat(token, notNullValue());
            assertThat(systemUsers.completeSuperadminSetup(token, username, PASSWORD).isPresent(), is(true));
        } finally {
            remove(systemUsers, username);
        }
    }

    @Test
    void anEarlierLinkStaysValidWhenAnotherInstanceLogsItsOwn() {
        SystemUserService systemUsers = Application.getInstance(SystemUserService.class);
        String username = "setup-keep-" + DbUtils.id().substring(0, 8);
        String first = systemUsers.createSuperadminSetup(username, null);

        try {
            String second = systemUsers.createInitialSetup(username);

            assertThat(systemUsers.completeSuperadminSetup(first, username, PASSWORD).isPresent(), is(true));
            assertThat("completing the setup ends every other link",
                    systemUsers.completeSuperadminSetup(second, username, PASSWORD).isPresent(), is(false));
        } finally {
            remove(systemUsers, username);
        }
    }

    // Other tests count the superadmins
    private static void remove(SystemUserService systemUsers, String username) {
        systemUsers.findPublicUserByUsername(username)
                .ifPresent(user -> AdminTestUtils.removeSuperadmin(String.valueOf(user.get("id"))));
    }
}
