package filters;

import constants.RequestAttributes;
import io.mangoo.interfaces.filters.OncePerRequestFilter;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import utils.DbUtils;

/**
 * Runs before every other filter of every controller route (mangoo calls the bound
 * {@link OncePerRequestFilter} first), which makes it the only place where the total execution
 * time can be measured from: anything later would silently exclude authentication, hooks, or
 * whatever else a route puts in front of its action.
 * <p>
 * It only stamps the request; the entry itself is written once the response exists.
 */
public class RequestLogFilter implements OncePerRequestFilter {
    @Override
    public Response execute(Request request, Response response) {
        request.addAttribute(RequestAttributes.REQUEST_START, System.nanoTime());
        request.addAttribute(RequestAttributes.REQUEST_ID, DbUtils.id());

        return response;
    }
}
