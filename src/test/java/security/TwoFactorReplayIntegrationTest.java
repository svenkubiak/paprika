package security;

import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.utils.TotpUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TwoFactorService;
import utils.DbUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ExtendWith({TestRunner.class})
class TwoFactorReplayIntegrationTest {

    @Test
    void aCodeIsAcceptedOncePerAccount() {
        TwoFactorService twoFactor = Application.getInstance(TwoFactorService.class);
        String secret = twoFactor.generateSecret();
        String userId = DbUtils.id();
        String code = TotpUtils.getTotp(secret);

        assertThat(twoFactor.verifyCode(userId, secret, code), is(true));
        assertThat("a replayed code must be refused", twoFactor.verifyCode(userId, secret, code), is(false));
    }

    @Test
    void anotherAccountKeepsItsOwnCode() {
        TwoFactorService twoFactor = Application.getInstance(TwoFactorService.class);
        String secret = twoFactor.generateSecret();
        String code = TotpUtils.getTotp(secret);

        assertThat(twoFactor.verifyCode(DbUtils.id(), secret, code), is(true));
        assertThat(twoFactor.verifyCode(DbUtils.id(), secret, code), is(true));
    }
}
