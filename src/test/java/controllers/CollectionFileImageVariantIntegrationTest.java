package controllers;

import auth.TenantContext;
import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionRules;
import models.FieldDefinition;
import models.FieldOptions;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import utils.DbUtils;
import utils.TenantTestUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * The download side of the image variants: an exact hit, the fallback chain, the header naming
 * what was delivered, and the rejection of a width that is not a width at all.
 */
@ExtendWith({TestRunner.class})
class CollectionFileImageVariantIntegrationTest {

    @Test
    void variantsAreDeliveredWithTheWidthTheyWereProducedAt() throws Exception {
        String collection = seed(List.of(100, 300));
        String recordId = upload(collection, image(800, 400));

        HttpResponse<byte[]> exact = download(collection, recordId, "?width=100");
        assertThat(exact.statusCode(), is(200));
        assertThat(header(exact, "X-Image-Width"), is("100"));
        assertThat(widthOf(exact.body()), is(100));

        // No 150 px variant exists, so the next *larger* one answers: a too small image is a
        // visible defect, a too large one only costs bandwidth.
        HttpResponse<byte[]> larger = download(collection, recordId, "?width=150");
        assertThat(header(larger, "X-Image-Width"), is("300"));
        assertThat(widthOf(larger.body()), is(300));

        // Wider than every variant: the original, and the header says so.
        HttpResponse<byte[]> original = download(collection, recordId, "?width=5000");
        assertThat(header(original, "X-Image-Width"), is("original"));
        assertThat(widthOf(original.body()), is(800));

        HttpResponse<byte[]> withoutWidth = download(collection, recordId, "");
        assertThat(header(withoutWidth, "X-Image-Width"), is("original"));
        assertThat(widthOf(withoutWidth.body()), is(800));
    }

    @Test
    void theEtagDistinguishesTheVariants() throws Exception {
        String collection = seed(List.of(100));
        String recordId = upload(collection, image(800, 400));

        String variantEtag = header(download(collection, recordId, "?width=100"), "ETag");
        String originalEtag = header(download(collection, recordId, ""), "ETag");

        assertThat(variantEtag, notNullValue());
        assertThat(variantEtag, org.hamcrest.Matchers.not(equalTo(originalEtag)));

        // The validator of one width must never short-circuit a request for another.
        assertThat(download(collection, recordId, "?width=100", variantEtag).statusCode(), is(304));
        assertThat(download(collection, recordId, "", variantEtag).statusCode(), is(200));
    }

    @Test
    void aWidthThatIsNotAPositiveNumberIsAFourHundred() throws Exception {
        String collection = seed(List.of(100));
        String recordId = upload(collection, image(400, 200));

        assertThat(download(collection, recordId, "?width=0").statusCode(), is(StatusCodes.BAD_REQUEST));
        assertThat(download(collection, recordId, "?width=-5").statusCode(), is(StatusCodes.BAD_REQUEST));
        assertThat(download(collection, recordId, "?width=abc").statusCode(), is(StatusCodes.BAD_REQUEST));
    }

    @Test
    void aWidthOnANonImageDeliversTheOriginal() throws Exception {
        String collection = seed(List.of(100));
        String recordId = uploadText(collection, "just text");

        HttpResponse<byte[]> response = download(collection, recordId, "?width=100");

        assertThat(response.statusCode(), is(200));
        assertThat(header(response, "X-Image-Width"), is("original"));
        assertThat(new String(response.body(), StandardCharsets.UTF_8), is("just text"));
    }

    @Test
    void deletingTheFileRemovesItsVariantsFromStorage() throws Exception {
        String collection = seed(List.of(100));
        String recordId = upload(collection, image(800, 400));

        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        Document record = Application.getInstance(TenantCollectionService.class)
                .dataCollection(ctx, collection)
                .find(com.mongodb.client.model.Filters.eq("id", recordId))
                .first();
        String fileId = ((Document) record.get("picture")).getString("id");
        services.FileStorageService storage = Application.getInstance(services.FileStorageService.class);
        assertThat(storage.variantWidths(ctx, fileId), is(List.of(100)));

        TestResponse deleted = TestRequest.delete(
                "/api/collections/" + collection + "/" + recordId + "/files/picture").execute();
        assertThat(deleted.getStatusCode(), equalTo(StatusCodes.OK));

        assertThat(storage.read(ctx, fileId), is(nullValue()));
        assertThat(storage.variantWidths(ctx, fileId), is(List.of()));
    }

    private static String seed(List<Integer> widths) {
        String collection = "docs_variants_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "*", "*", "*", null),
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition("picture", FieldType.FILE, true, false,
                                FieldOptions.forFile(5L * 1024 * 1024, List.of(), 1, widths))));
        return collection;
    }

    private static String upload(String collection, byte[] png) throws Exception {
        return uploadBytes(collection, png, "picture.png", "image/png");
    }

    private static String uploadText(String collection, String content) throws Exception {
        return uploadBytes(collection, content.getBytes(StandardCharsets.UTF_8), "note.txt", "text/plain");
    }

    /**
     * Posts the multipart body over a raw HTTP client: the payload is binary, so it cannot go
     * through the string-based test request without being mangled by the charset round-trip.
     */
    private static String uploadBytes(String collection, byte[] content, String fileName, String mimeType)
            throws Exception {

        String boundary = "----paprika-variants";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"title\"\r\n\r\n"
                + "hello\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"picture\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + mimeType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/api/collections/" + collection))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.body(), response.statusCode(), is(201));

        Document record = Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), collection)
                .find()
                .first();
        assertThat(record, notNullValue());
        return record.getString("id");
    }

    private static HttpResponse<byte[]> download(String collection, String recordId, String query) throws Exception {
        return download(collection, recordId, query, null);
    }

    private static HttpResponse<byte[]> download(
            String collection, String recordId, String query, String ifNoneMatch) throws Exception {

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                baseUrl() + "/api/collections/" + collection + "/" + recordId + "/files/picture" + query));
        if (ifNoneMatch != null) {
            request = request.header("If-None-Match", ifNoneMatch);
        }
        return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String baseUrl() {
        return "http://localhost:" + Application.getInstance(io.mangoo.core.Config.class).getConnectorHttpPort();
    }

    private static String header(HttpResponse<byte[]> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    private static int widthOf(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes)).getWidth();
    }

    private static byte[] image(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
