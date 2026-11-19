package app;

import io.mangoo.routing.Router;
import io.mangoo.routing.routes.RequestRoute;
import io.mangoo.test.TestRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * A blocking hook waits on the request thread. Small JSON and every GET/DELETE stay on the
 * Undertow I/O thread unless the route calls {@code withNonBlocking()}, so these routes run on a
 * worker. Admin, meta, realtime and backup routes do not call a blocking tenant hook.
 */
@ExtendWith({TestRunner.class})
class BlockingHookRoutesTest {
    private static final Set<String> WORKER_ROUTES = Set.of(
            "POST /api/auth/register",
            "POST /api/auth/login",
            "POST /api/auth/refresh",
            "POST /api/auth/logout",
            "POST /api/auth/issue-token",
            "GET /api/auth/me",
            "POST /api/auth/password/forgot",
            "POST /api/auth/password/reset",
            "POST /api/auth/verify/request",
            "POST /api/auth/verify/confirm",
            "GET /api/collections/{collection}",
            "POST /api/collections/{collection}",
            "GET /api/collections/{collection}/{id}",
            "PATCH /api/collections/{collection}/{id}",
            "DELETE /api/collections/{collection}/{id}",
            "GET /api/collections/{collection}/{id}/files/{field}",
            "GET /api/collections/{collection}/{id}/files/{field}/{fileId}",
            "DELETE /api/collections/{collection}/{id}/files/{field}",
            "DELETE /api/collections/{collection}/{id}/files/{field}/{fileId}"
    );

    @Test
    void routesThatCallABlockingHookLeaveTheIoThread() {
        List<RequestRoute> routes = Router.getRequestRoutes().toList();
        assertThat("the routing table must be readable, otherwise this test proves nothing",
                routes, not(empty()));

        List<String> registered = new ArrayList<>();
        for (RequestRoute route : routes) {
            String key = route.getMethod().name() + " " + route.getUrl();
            registered.add(key);
            if (WORKER_ROUTES.contains(key)) {
                assertThat(key + " calls a blocking hook and must run on a worker",
                        route.isBlocking(), is(true));
            } else {
                assertThat(key + " does not call a blocking tenant hook",
                        route.isBlocking(), is(false));
            }
        }

        List<String> missing = WORKER_ROUTES.stream()
                .filter(key -> !registered.contains(key))
                .sorted()
                .toList();
        assertThat(missing, is(empty()));
    }
}
