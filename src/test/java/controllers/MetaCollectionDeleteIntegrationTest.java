package controllers;

import auth.TenantContext;
import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.undertow.util.StatusCodes;
import models.CollectionDefinition;
import models.FieldDefinition;
import models.FieldOptions;
import models.FileReference;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.FileStorageService;
import services.TenantCollectionService;
import utils.AdminTestUtils;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@ExtendWith({TestRunner.class})
class MetaCollectionDeleteIntegrationTest {

    @Test
    void deletingACollectionRemovesItsFilesAndASecondDeleteIsNotFound() throws Exception {
        String name = "drop_files_" + DbUtils.id();
        TenantTestUtils.seedCollection(name, null, List.of(new FieldDefinition(
                "attachment", FieldType.FILE, false, true, FieldOptions.forFile(1024, List.of(), 1))));
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        CollectionDefinition definition = collections.findDefinition(ctx, name);
        String fileId = DbUtils.id();
        FileStorageService storage = Application.getInstance(FileStorageService.class);
        storage.store(ctx, fileId, "hello".getBytes(StandardCharsets.UTF_8));
        collections.dataCollection(ctx, name).insertOne(new Document("id", DbUtils.id())
                .append("attachment", new FileReference(fileId, "hello.txt", "text/plain", 5).toDocument()));

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();
        String path = "/api/meta/collections/" + name + "/";

        assertThat(AdminTestUtils.deleteWithAdminCookies(path + DbUtils.id(), cookies).getStatusCode(),
                equalTo(StatusCodes.NOT_FOUND));
        assertThat(new String(storage.read(ctx, fileId), StandardCharsets.UTF_8), equalTo("hello"));

        assertThat(AdminTestUtils.deleteWithAdminCookies(path + definition.id(), cookies).getStatusCode(),
                equalTo(StatusCodes.OK));
        assertThat(storage.read(ctx, fileId), nullValue());
        assertThat(collections.findDefinition(ctx, name), nullValue());

        assertThat(AdminTestUtils.deleteWithAdminCookies(path + definition.id(), cookies).getStatusCode(),
                equalTo(StatusCodes.NOT_FOUND));
    }

    @Test
    void systemCollectionsCannotBeDeleted() {
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        CollectionDefinition users = collections.findDefinition(ctx, "users");
        assertThat(users.isSystem(), equalTo(true));

        var response = AdminTestUtils.deleteWithAdminCookies(
                "/api/meta/collections/users/" + users.id(),
                AdminTestUtils.loginAsAdminWithDefaultTenant());

        assertThat(response.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
        assertThat(response.getContent(), containsString("System collections cannot be deleted"));
        assertThat(collections.findDefinition(ctx, "users").id(), equalTo(users.id()));
    }
}
