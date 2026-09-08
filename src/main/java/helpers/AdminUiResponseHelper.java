package helpers;

import io.mangoo.routing.Response;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class AdminUiResponseHelper {
    private static final String INDEX_RESOURCE = "/files/assets/index.html";
    private static final String NOT_BUILT_MESSAGE =
            "Admin UI not built. Run `npm install && npm run build` in admin-ui.";

    private AdminUiResponseHelper() {
    }

    public static Response render() {
        try (InputStream inputStream = AdminUiResponseHelper.class.getResourceAsStream(INDEX_RESOURCE)) {
            if (inputStream == null) {
                return Response.notFound().bodyText(NOT_BUILT_MESSAGE);
            }

            return Response.ok()
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .header("Pragma", "no-cache")
                    .bodyHtml(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Response.notFound().bodyText(NOT_BUILT_MESSAGE);
        }
    }
}
