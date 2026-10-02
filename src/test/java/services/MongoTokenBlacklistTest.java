package services;

import io.mangoo.core.Application;
import io.mangoo.core.Config;
import io.mangoo.interfaces.TokenBlacklist;
import io.mangoo.test.TestRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.DbUtils;

import java.time.Duration;
import java.time.Instant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/** Each check reads through a second instance, as a restarted application would. */
@ExtendWith({TestRunner.class})
class MongoTokenBlacklistTest {

    @Test
    void mangooUsesTheMongoBackedBlacklist() {
        assertThat(Application.getInstance(TokenBlacklist.class), instanceOf(MongoTokenBlacklist.class));
    }

    @Test
    void aRevokedTokenStaysRevokedForAFreshInstance() {
        String jwtId = DbUtils.id();
        blacklist().revoke(jwtId, Instant.now().plus(Duration.ofHours(1)));

        assertThat(blacklist().isRevoked(jwtId, null, null), equalTo(true));
        assertThat(blacklist().isRevoked(DbUtils.id(), null, null), equalTo(false));
    }

    @Test
    void anAlreadyExpiredTokenIsNotStored() {
        String jwtId = DbUtils.id();
        blacklist().revoke(jwtId, Instant.now().minusSeconds(1));

        assertThat(blacklist().isRevoked(jwtId, null, null), equalTo(false));
    }

    @Test
    void aSubjectRevocationCatchesOnlyTokensIssuedBeforeIt() {
        String subject = "subject-" + DbUtils.id();
        Instant since = Instant.now();
        blacklist().revokeSubject(subject, since);

        assertThat(blacklist().isRevoked(DbUtils.id(), subject, since.minusSeconds(5)), equalTo(true));
        assertThat("a sign-in after the revocation stays valid",
                blacklist().isRevoked(DbUtils.id(), subject, since.plusSeconds(5)), equalTo(false));
        assertThat("another account is not affected",
                blacklist().isRevoked(DbUtils.id(), "other-" + subject, since.minusSeconds(5)), equalTo(false));
    }

    @Test
    void anEarlierSubjectRevocationDoesNotShortenALaterOne() {
        String subject = "subject-" + DbUtils.id();
        Instant later = Instant.now();
        blacklist().revokeSubject(subject, later);
        blacklist().revokeSubject(subject, later.minus(Duration.ofMinutes(10)));

        assertThat(blacklist().isRevoked(DbUtils.id(), subject, later.minusSeconds(5)), equalTo(true));
    }

    private static MongoTokenBlacklist blacklist() {
        return new MongoTokenBlacklist(Application.getInstance(TenantDatabaseResolver.class),
                Application.getInstance(Config.class));
    }
}
