package services;

import org.junit.jupiter.api.Test;
import services.storage.S3Response;
import utils.MultipartSupport;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * These values carry file bytes. Equality follows the bytes, and the text form reports the length,
 * so a log line never contains the file itself.
 */
class StoredBytesEqualityTest {

    @Test
    void aDownloadIsTheSameWhenTheBytesAndTheDescriptorMatch() {
        byte[] png = {1, 2, 3};
        CollectionFileService.FileDownloadResult found =
                CollectionFileService.FileDownloadResult.found(png, "image/png", "a.png", "file-1", 320);
        CollectionFileService.FileDownloadResult copy =
                CollectionFileService.FileDownloadResult.found(new byte[] {1, 2, 3}, "image/png", "a.png", "file-1", 320);

        assertThat(found, equalTo(found));
        assertThat(found, equalTo(copy));
        assertThat(found.hashCode(), equalTo(copy.hashCode()));
        assertThat(found, not(equalTo(CollectionFileService.FileDownloadResult.found(
                new byte[] {1, 2, 4}, "image/png", "a.png", "file-1", 320))));
        assertThat(found, not(equalTo(CollectionFileService.FileDownloadResult.found(
                png, "image/jpeg", "a.png", "file-1", 320))));
        assertThat(found, not(equalTo(CollectionFileService.FileDownloadResult.found(
                png, "image/png", "b.png", "file-1", 320))));
        assertThat(found, not(equalTo(CollectionFileService.FileDownloadResult.found(
                png, "image/png", "a.png", "file-2", 320))));
        assertThat(found, not(equalTo(CollectionFileService.FileDownloadResult.found(
                png, "image/png", "a.png", "file-1", 160))));
        assertThat(found, not(equalTo(CollectionFileService.FileDownloadResult.notFound())));
        assertThat(found, not(equalTo("file")));
        assertThat(CollectionFileService.FileDownloadResult.notFound(),
                equalTo(CollectionFileService.FileDownloadResult.notFound()));
        assertThat(found.toString(), containsString("3 bytes"));
        assertThat(CollectionFileService.FileDownloadResult.error().toString(), containsString("bytes=null"));
    }

    @Test
    void anUploadAnAvatarAndAStoredObjectFollowTheirBytes() {
        byte[] left = {9, 8};
        byte[] right = {9, 8};

        MultipartSupport.UploadedFile upload = new MultipartSupport.UploadedFile("a.txt", left, "text/plain");
        MultipartSupport.UploadedFile same = new MultipartSupport.UploadedFile("a.txt", right, "text/plain");
        assertThat(upload, equalTo(upload));
        assertThat(upload, equalTo(same));
        assertThat(upload.hashCode(), equalTo(same.hashCode()));
        assertThat(upload, not(equalTo(new MultipartSupport.UploadedFile("b.txt", right, "text/plain"))));
        assertThat(upload, not(equalTo(new MultipartSupport.UploadedFile("a.txt", new byte[] {9}, "text/plain"))));
        assertThat(upload, not(equalTo(new MultipartSupport.UploadedFile("a.txt", right, "text/html"))));
        assertThat(upload, not(equalTo("file")));
        assertThat(upload.toString(), containsString("2 bytes"));

        SystemUserService.Avatar avatar = new SystemUserService.Avatar(left, "image/png", "v1");
        SystemUserService.Avatar avatarCopy = new SystemUserService.Avatar(right, "image/png", "v1");
        assertThat(avatar, equalTo(avatarCopy));
        assertThat(avatar.hashCode(), equalTo(avatarCopy.hashCode()));
        assertThat(avatar, not(equalTo(new SystemUserService.Avatar(left, "image/png", "v2"))));
        assertThat(avatar, not(equalTo(new SystemUserService.Avatar(null, "image/png", "v1"))));
        assertThat(avatar, not(equalTo("avatar")));
        assertThat(avatar.toString(), containsString("2 bytes"));
        assertThat(new SystemUserService.Avatar(null, "image/png", "v1").toString(), containsString("data=null"));

        S3Response response = new S3Response(200, left);
        assertThat(response, equalTo(new S3Response(200, right)));
        assertThat(response.hashCode(), equalTo(new S3Response(200, right).hashCode()));
        assertThat(response, not(equalTo(new S3Response(404, right))));
        assertThat(response, not(equalTo(new S3Response(200, null))));
        assertThat(response, not(equalTo("response")));
        assertThat(new S3Response(200, null), equalTo(new S3Response(200, null)));
        assertThat(response.toString(), containsString("2 bytes"));
        assertThat(new S3Response(200, null).toString(), containsString("body=null"));

        FileFieldService.VariantDelivery variant = new FileFieldService.VariantDelivery(left, 320);
        assertThat(variant, equalTo(new FileFieldService.VariantDelivery(right, 320)));
        assertThat(variant.hashCode(), equalTo(new FileFieldService.VariantDelivery(right, 320).hashCode()));
        assertThat(variant, not(equalTo(new FileFieldService.VariantDelivery(left, 160))));
        assertThat(variant, not(equalTo(new FileFieldService.VariantDelivery(left, null))));
        assertThat(variant, not(equalTo("variant")));
        assertThat(variant.toString(), containsString("2 bytes"));
    }
}
