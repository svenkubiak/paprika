package helpers;

import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import io.undertow.util.StatusCodes;
import org.apache.commons.lang3.StringUtils;
import services.CollectionFileService;
import services.RequestLogService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class CollectionFileResponseHelper {
    private CollectionFileResponseHelper() {
    }

    public static Response toDownloadResponse(
            Request request,
            CollectionFileService.FileDownloadResult result,
            RequestLogService requestLogService) {

        Response response = switch (result.status()) {
            case FOUND -> {
                boolean attachment = "1".equals(request.getParameter("download"))
                        || "true".equalsIgnoreCase(StringUtils.defaultString(request.getParameter("download")));
                String disposition = (attachment ? "attachment" : "inline")
                        + "; filename=\""
                        + URLEncoder.encode(result.fileName(), StandardCharsets.UTF_8).replace("+", "%20")
                        + "\"";
                yield Response.ok()
                        .contentType(result.mimeType())
                        .header("Content-Disposition", disposition)
                        .bodyBinary(result.bytes());
            }
            case NOT_FOUND -> Response.notFound().end();
            case ERROR -> Response.internalServerError().end();
        };

        return requestLogService.track(request, response);
    }

    public static Response toDeleteResponse(
            Request request,
            CollectionFileService.FileDeleteResult result,
            RequestLogService requestLogService) {

        Response response = switch (result.status()) {
            case SUCCESS -> Response.ok().bodyJson(Map.of("success", true));
            case NOT_FOUND -> Response.notFound().end();
            case CONFLICT -> Response.status(StatusCodes.CONFLICT).end();
        };

        return requestLogService.track(request, response);
    }
}
