package helpers;

import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import io.undertow.util.StatusCodes;
import org.apache.commons.lang3.StringUtils;
import services.CollectionFileService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

public final class CollectionFileResponseHelper {
    // Only types that cannot carry active content are served inline; everything else (html, svg,
    // xml, unknown) is forced to attachment.
    private static final Set<String> INLINE_SAFE_MIME_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/webp",
            "application/pdf");

    // Sandboxes independently of the app CSP, so even an allowlist mistake cannot execute content.
    private static final String DOWNLOAD_CONTENT_SECURITY_POLICY = "default-src 'none'; sandbox";

    // private: access is decided by collection rules, so a shared cache must never serve another caller.
    // Short max-age because a single-file field's URL carries no file id and changes content on replace.
    private static final String CACHE_CONTROL = "private, max-age=300, immutable";

    // Lets a caller tell an exact hit from a fallback to another variant or the original.
    private static final String IMAGE_WIDTH_HEADER = "X-Image-Width";
    private static final String ORIGINAL_WIDTH = "original";

    private CollectionFileResponseHelper() {
    }

    public static Response toDownloadResponse(
            Request request,
            CollectionFileService.FileDownloadResult result) {

        return switch (result.status()) {
            case FOUND -> {
                String etag = etagOf(result);
                if (etag.equals(request.getHeader("If-None-Match"))) {
                    yield Response.notModified()
                            .header("ETag", etag)
                            .header("Cache-Control", CACHE_CONTROL)
                            .header(IMAGE_WIDTH_HEADER, deliveredWidth(result))
                            .end();
                }

                boolean requestedAttachment = "1".equals(request.getQueryParameter("download"))
                        || "true".equalsIgnoreCase(StringUtils.defaultString(request.getQueryParameter("download")));
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
                        .header("ETag", etag)
                        .header("Cache-Control", CACHE_CONTROL)
                        .header(IMAGE_WIDTH_HEADER, deliveredWidth(result))
                        .bodyBinary(result.bytes());
            }
            case NOT_FOUND -> Response.notFound().end();
            case ERROR -> Response.internalServerError().end();
        };
    }

    // Every stored file gets a new id, so it identifies the bytes; the variant must be included or a
    // cache would answer one width with another.
    private static String etagOf(CollectionFileService.FileDownloadResult result) {
        return "\"" + result.fileId() + "-" + deliveredWidth(result) + "\"";
    }

    private static String deliveredWidth(CollectionFileService.FileDownloadResult result) {
        return result.deliveredWidth() == null ? ORIGINAL_WIDTH : String.valueOf(result.deliveredWidth());
    }

    public static Response toDeleteResponse(CollectionFileService.FileDeleteResult result) {
        return switch (result.status()) {
            case SUCCESS -> Response.ok().bodyJson(Map.of("success", true));
            case NOT_FOUND -> Response.notFound().end();
            case CONFLICT -> Response.status(StatusCodes.CONFLICT).end();
        };
    }
}
