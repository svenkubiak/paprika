package controllers;

import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.core.Config;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionRules;
import models.FieldDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Guards the {@code application.parameter.strict} setting: a query parameter that collides with a
 * route parameter of the matched route is rejected instead of being silently ignored.
 */
@ExtendWith({TestRunner.class})
class ParameterStrictIntegrationTest {

    @Test
    void strictParameterModeIsEnabled() {
        assertThat(Application.getInstance(Config.class).isParameterStrict(), is(true));
    }

    @Test
    void queryParameterCollidingWithRouteParameterIsRejected() {
        String collection = "notes_strict_" + DbUtils.id();
        seedPublicCollection(collection);

        TestResponse collision = TestRequest.get(
                "/api/collections/" + collection + "?offset=0&limit=25&collection=users").execute();

        assertThat(collision.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
    }

    @Test
    void ordinaryQueryParametersStillWork() {
        String collection = "notes_strict_ok_" + DbUtils.id();
        seedPublicCollection(collection);
        TenantTestUtils.seedRecord(collection, "PUBLIC-RECORD");

        TestResponse list = TestRequest.get(
                "/api/collections/" + collection + "?offset=0&limit=25").execute();

        assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(list.getContent(), containsString("PUBLIC-RECORD"));
    }

    private void seedPublicCollection(String collection) {
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "*", "*", "*", "owner"),
                List.of(new FieldDefinition("title", FieldType.STRING, true, false, null)));
    }
}
