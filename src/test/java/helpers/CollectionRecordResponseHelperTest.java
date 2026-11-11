package helpers;

import io.undertow.util.StatusCodes;
import org.junit.jupiter.api.Test;
import services.CollectionRecordService.RecordResult;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;

/** Each record outcome has one HTTP shape. A missing body or message must not invent one. */
class CollectionRecordResponseHelperTest {

    @Test
    void mapsEveryRecordOutcomeToItsHttpStatus() {
        assertThat(status(RecordResult.created(Map.of("id", "1"))), equalTo(StatusCodes.CREATED));
        assertThat(body(RecordResult.created(Map.of("id", "1"))), containsString("\"id\""));
        assertThat(status(RecordResult.created()), equalTo(StatusCodes.CREATED));
        assertThat(body(RecordResult.created()), emptyString());

        assertThat(status(RecordResult.ok(Map.of("title", "kept"))), equalTo(StatusCodes.OK));
        assertThat(body(RecordResult.ok(Map.of("title", "kept"))), containsString("kept"));
        assertThat(status(RecordResult.ok()), equalTo(StatusCodes.OK));
        assertThat(body(RecordResult.ok()), emptyString());

        assertThat(status(RecordResult.notFound()), equalTo(StatusCodes.NOT_FOUND));
        assertThat(status(RecordResult.conflict()), equalTo(StatusCodes.CONFLICT));
        assertThat(status(RecordResult.badRequest("Title is required")), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(body(RecordResult.badRequest("Title is required")), containsString("Title is required"));
        assertThat(status(RecordResult.badRequest(null)), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(body(RecordResult.badRequest(null)), emptyString());
        assertThat(status(RecordResult.forbidden()), equalTo(StatusCodes.FORBIDDEN));
        assertThat(body(RecordResult.forbidden()), containsString("Forbidden"));
        assertThat(status(RecordResult.tooManyRequests()), equalTo(StatusCodes.TOO_MANY_REQUESTS));
        assertThat(body(RecordResult.tooManyRequests()), containsString("Too many authentication requests"));
        assertThat(status(RecordResult.error()), equalTo(StatusCodes.INTERNAL_SERVER_ERROR));
    }

    private static int status(RecordResult result) {
        return CollectionRecordResponseHelper.toResponse(result).getStatusCode();
    }

    private static String body(RecordResult result) {
        return CollectionRecordResponseHelper.toResponse(result).getBody();
    }
}
