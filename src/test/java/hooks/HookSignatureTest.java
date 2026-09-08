package hooks;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

class HookSignatureTest {

    @Test
    void signsPayloadWithSha256Prefix() {
        String signature = HookSignature.sign("secret", "{\"hello\":\"world\"}");
        assertThat(signature.startsWith("sha256="), equalTo(true));
        assertThat(signature.length(), not(equalTo("sha256=".length())));
    }

    @Test
    void sameInputProducesSameSignature() {
        String first = HookSignature.sign("secret", "{\"event\":\"beforeCreate\"}");
        String second = HookSignature.sign("secret", "{\"event\":\"beforeCreate\"}");
        assertThat(first, equalTo(second));
    }
}
