package auth;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/** The map is what a hook and a rule see. A missing part is an empty string, never a null entry. */
class AuthContextTest {

    @Test
    void aGuestAndASignedInCallerExposeTheSameThreeFields() {
        assertThat(AuthContext.guest().asMap(), is(Map.of("id", "", "role", "", "tenantId", "")));
        assertThat(AuthContext.guest().isAuthenticated(), is(false));
        assertThat(AuthContext.of("  ", "user", null).isAuthenticated(), is(false));

        AuthContext user = AuthContext.of("user-1", "user", "tenant-1");
        assertThat(user.asMap(), is(Map.of("id", "user-1", "role", "user", "tenantId", "tenant-1")));
        assertThat(user.getField("id"), is("user-1"));
        assertThat(user.getField("role"), is("user"));
        assertThat(user.getField("tenantId"), is("tenant-1"));
        assertThat(user.getField("email"), nullValue());
        assertThat(user.isSuperAdmin(), is(false));
        assertThat(AuthContext.of("admin", "superadmin", null).isSuperAdmin(), is(true));
    }
}
