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
import java.util.Set;

public final class CollectionFileResponseHelper {
    // Only mime types with no ability to carry active/renderable content may ever be served inline.
    // Everything else (text/html, image/svg+xml, xml variants, unknown types, ...) is forced to attachment.
    private static final Set<String> INLINE_SAFE_MIME_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/webp",
            "application/pdf");

    // Sandboxes the response independently of the application-wide CSP, so inline delivery
    // (or a future mistake in the allowlist above) still can't execute active content.
    private static final String DOWNLOAD_CONTENT_SECURITY_POLICY = "default-src 'none'; sandbox";

    private CollectionFileResponseHelper() {
    }

    public static Response toDownloadResponse(
            Request request,
            CollectionFileService.FileDownloadResult result,
            RequestLogService requestLogService) {

        Response response = switch (result.status()) {
            case FOUND -> {
                boolean requestedAttachment = "1".equals(request.getParameter("download"))
                        || "true".equalsIgnoreCase(StringUtils.defaultString(request.getParameter("download")));
                boolean attachment = requestedAttachment || !INLINE_SAFE_MIME_TYPES.contains(result.mimeType());
                String disposition = (attachment ? "attachment" : "inline")
                        + "; filename=\""
                        + URLEncoder.encode(result.fileName(), StandardCharsets.UTF_8).replace("+", "%20")
                        + "\"";
                yield Response.ok()
                        .contentType(result.mimeType())
                        .header("Content-Disposition", disposition)
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", DOWNLOAD_CONTENT_SECURITY_POLICY)
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
