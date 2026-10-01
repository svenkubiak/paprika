package filters;

import constants.RequestAttributes;
import io.mangoo.interfaces.filters.OncePerRequestFilter;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import utils.DbUtils;

// mangoo runs the bound OncePerRequestFilter before every other filter, so only here does the
// measured time include auth and hooks. The log entry is written later, in the response handler.
public class RequestLogFilter implements OncePerRequestFilter {
    @Override
    public Response execute(Request request, Response response) {
        request.addAttribute(RequestAttributes.REQUEST_START, System.nanoTime());
        request.addAttribute(RequestAttributes.REQUEST_ID, DbUtils.id());

        return response;
    }
}
