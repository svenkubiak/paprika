package utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class MimeTypesTest {

    @Test
    void detectsJpegFromMagicBytes() {
        byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};
        assertThat(MimeTypes.detect(jpeg, "upload.bin"), is("image/jpeg"));
    }

    @Test
    void detectsFromFileNameWhenDataEmpty() {
        assertThat(MimeTypes.detect(new byte[0], "photo.png"), is("image/png"));
        assertThat(MimeTypes.detect(null, "report.pdf"), is("application/pdf"));
        assertThat(MimeTypes.detect(null, "photo.JPEG"), is("image/jpeg"));
        assertThat(MimeTypes.detect(null, "photo.jpg"), is("image/jpeg"));
        assertThat(MimeTypes.detect(null, "anim.GIF"), is("image/gif"));
        assertThat(MimeTypes.detect(null, "still.webp"), is("image/webp"));
        assertThat(MimeTypes.detect(null, "notes.TXT"), is("text/plain"));
        assertThat(MimeTypes.detect(null, "file.bin"), is("application/octet-stream"));
        assertThat(MimeTypes.detect(null, "  "), is("application/octet-stream"));
        assertThat(MimeTypes.detect(null, null), is("application/octet-stream"));
    }

    @Test
    void detectsPdfFromMagicBytes() {
        byte[] pdf = "%PDF-1.4".getBytes();
        assertThat(MimeTypes.detect(pdf, "upload.bin"), is("application/pdf"));
    }

    @Test
    void matchesWildcardMimeType() {
        assertThat(MimeTypes.matches("image/png", List.of("image/*")), is(true));
        assertThat(MimeTypes.matches("application/pdf", List.of("image/*")), is(false));
    }

    @Test
    void matchesExactMimeType() {
        assertThat(MimeTypes.matches("application/pdf", List.of("application/pdf")), is(true));
        assertThat(MimeTypes.matches("image/png", null), is(true));
        assertThat(MimeTypes.matches("image/png", List.of()), is(true));
        assertThat(MimeTypes.matches(" ", List.of("image/png")), is(false));
        assertThat(MimeTypes.matches("image/png", java.util.Arrays.asList(" ", "image/png")), is(true));
    }
}
