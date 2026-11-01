package services;

import io.mangoo.core.Application;
import io.mangoo.interfaces.TokenBlacklist;
import io.mangoo.persistence.MongoTokenBlacklist;
import io.mangoo.test.TestRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;

/** Revocations must reach every instance and survive a restart, which the in-memory default does not. */
@ExtendWith({TestRunner.class})
class TokenBlacklistBindingTest {

    @Test
    void revocationsAreKeptInMongoDb() {
        assertThat(Application.getInstance(TokenBlacklist.class), instanceOf(MongoTokenBlacklist.class));
    }
}
