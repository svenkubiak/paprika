package security;

import enums.FieldType;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionRules;
import models.FieldDefinition;
import models.FieldOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.time.Duration;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The end-to-end shape of the pattern problem: a tenant admin configures a pattern that
 * backtracks, and from then on any caller who may create a record decides how long a worker
 * thread is busy. With a public create rule that caller is unauthenticated, and a handful of
 * requests is enough to take the instance down for every tenant - which no rate limit in front
 * of it prevents, because the requests themselves are perfectly ordinary.
 */
@ExtendWith({TestRunner.class})
class PatternDenialOfServiceIntegrationTest {
    private static final Duration BUDGET = Duration.ofSeconds(15);
    private static final String CATASTROPHIC_PATTERN = "^(a+)+\\1$";

    private static String collection;

    @BeforeAll
    static void seed() {
        collection = "redos_items_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "*", "*", "*", "owner"),
                List.of(new FieldDefinition(
                        "code",
                        FieldType.STRING,
                        true,
                        false,
                        FieldOptions.forString(null, 64, CATASTROPHIC_PATTERN))));
    }

    /**
     * Within the configured maxLength, so nothing but the match budget can stop this one. 40
     * characters is roughly 2^40 backtracking steps - the request would never come back.
     */
    @Test
    void aValueThatMakesThePatternBacktrackIsAnsweredInsteadOfHangingTheWorker() {
        String value = "a".repeat(40) + "b";

        TestResponse response = assertTimeoutPreemptively(BUDGET, () -> create(value));

        assertThat("a value that could not be matched has to be rejected, not accepted",
                response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
    }

    /** Beyond maxLength the value never reaches the engine at all. */
    @Test
    void aValueBeyondMaxLengthIsRejectedWithoutRunningThePattern() {
        String value = "a".repeat(100_000) + "b";

        TestResponse response = assertTimeoutPreemptively(BUDGET, () -> create(value));

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), org.hamcrest.Matchers.containsString("maxLength"));
    }

    /** A value the pattern accepts still goes through - the guard is not a blanket refusal. */
    @Test
    void aMatchingValueIsStillAccepted() {
        // "aa" splits into a group of one "a" plus the backreference, so this one matches.
        TestResponse response = assertTimeoutPreemptively(BUDGET, () -> create("aa"));

        assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.CREATED));
    }

    private static TestResponse create(String code) {
        return TestRequest.post("/api/collections/" + collection)
                .withStringBody("{\"code\":\"" + code + "\"}")
                .withContentType("application/json")
                .execute();
    }
}
