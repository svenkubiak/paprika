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

    // private: access to a file is decided by the collection rules, so a shared cache in front of
    // Paprika must never hand a stored copy to a different caller. This is not negotiable.
    //
    // The max-age is deliberately short even though a stored file is immutable: the download URL
    // of a single-file field carries no file id, so the same URL delivers a different file once
    // the field is replaced. Five minutes bounds how long a client can keep showing the previous
    // file while still removing the repeated transfers this header exists for; after that the
    // ETag turns the next request into a cheap 304.
    private static final String CACHE_CONTROL = "private, max-age=300, immutable";

    // Names the width that was actually delivered, so a caller can tell an exact hit from a
    // fallback - otherwise a configuration that never produced a variant looks exactly like one
    // that works.
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

    /**
     * A strong validator built from the id of the stored file and the delivered variant. Storing a
     * file always mints a new id, so the id identifies the bytes; the variant has to be part of
     * the validator as well, or a cache would answer a request for one width with another.
     */
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
