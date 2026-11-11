package controllers;

import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.AdminTestUtils;

import java.net.HttpCookie;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

@ExtendWith({TestRunner.class})
class BackupControllerTest {

    @Test
    void anAdminCanDownloadABackupAndAnImportWithoutAFileIsRefused() {
        TestResponse anonymous = TestRequest.get("/api/admin/backup/export").execute();
        assertThat(anonymous.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));

        HttpCookie auth = AdminTestUtils.loginAsAdmin();
        TestResponse exported = TestRequest.get("/api/admin/backup/export").withCookie(auth).execute();
        assertThat(exported.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(exported.getContentType(), containsString("application/zip"));
        assertThat(exported.getHeader("Content-Disposition"), containsString("paprika-backup-"));
        assertThat(exported.getHeader("Content-Disposition"), containsString(".zip"));
        assertThat(exported.getContent(), startsWith("PK"));

        String boundary = "----paprika-backup";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"note\"\r\n\r\n"
                + "no file here\r\n"
                + "--" + boundary + "--\r\n";
        TestResponse imported = TestRequest.post("/api/admin/backup/import")
                .withCookie(auth)
                .withHeader("Content-Type", "multipart/form-data; boundary=" + boundary)
                .withStringBody(body)
                .execute();
        assertThat(imported.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(imported.getContent(), containsString("No backup file uploaded"));

        String rejected = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"backup.zip\"\r\n"
                + "Content-Type: application/zip\r\n\r\n"
                + "this is not a zip\r\n"
                + "--" + boundary + "--\r\n";
        TestResponse notABackup = TestRequest.post("/api/admin/backup/import")
                .withCookie(auth)
                .withHeader("Content-Type", "multipart/form-data; boundary=" + boundary)
                .withStringBody(rejected)
                .execute();
        assertThat(notABackup.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(notABackup.getContent(), containsString("missing manifest.json"));
    }
}
