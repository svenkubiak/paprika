package services;

import auth.AuthContext;
import io.mangoo.core.Application;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(io.mangoo.test.TestRunner.class)
class UserServiceTest {

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = Application.getInstance(UserService.class);
    }

    @Test
    void createsAndAuthenticatesUser() {
        var created = userService.createUser("alice", "alice@example.com", "secret-password-123");

        assertThat(created.get("id"), notNullValue());
        assertThat(created.get("username"), is("alice"));

        Optional<AuthContext> auth = userService.authenticate("alice", "secret-password-123");
        assertThat(auth.isPresent(), is(true));
        assertThat(auth.get().id(), is(created.get("id")));
    }

    @Test
    void rejectsDuplicateUsername() {
        userService.createUser("bob", null, "secret-password-123");

        assertThrows(IllegalArgumentException.class, () ->
                userService.createUser("bob", null, "other-password-456"));
    }

    @Test
    void rejectsWrongPassword() {
        userService.createUser("carol", null, "secret-password-123");

        Optional<AuthContext> auth = userService.authenticate("carol", "wrong");
        assertThat(auth.isPresent(), is(false));
    }
}
