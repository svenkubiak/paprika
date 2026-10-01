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
 * A backtracking pattern plus a public create rule lets an unauthenticated caller pin worker threads
 * with ordinary requests that no rate limit stops.
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

    /** Within maxLength, so only the match budget can stop it: 40 chars is roughly 2^40 steps. */
    @Test
    void aValueThatMakesThePatternBacktrackIsAnsweredInsteadOfHangingTheWorker() {
        String value = "a".repeat(40) + "b";

        TestResponse response = assertTimeoutPreemptively(BUDGET, () -> create(value));

        assertThat("a value that could not be matched has to be rejected, not accepted",
                response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
    }

    @Test
    void aValueBeyondMaxLengthIsRejectedWithoutRunningThePattern() {
        String value = "a".repeat(100_000) + "b";

        TestResponse response = assertTimeoutPreemptively(BUDGET, () -> create(value));

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), org.hamcrest.Matchers.containsString("maxLength"));
    }

    @Test
    void aMatchingValueIsStillAccepted() {
        // "aa" splits into one "a" plus the backreference, so this one matches.
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
