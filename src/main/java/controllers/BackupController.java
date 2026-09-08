package controllers;

import filters.admin.AdminAuthFilter;
import io.mangoo.annotations.FilterWith;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import jakarta.inject.Inject;
import services.AuthService;
import services.ExportService;
import services.ImportService;
import utils.MultipartSupport;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@FilterWith(AdminAuthFilter.class)
public class BackupController {
    private final ExportService exportService;
    private final ImportService importService;
    private final AuthService authService;

    @Inject
    public BackupController(ExportService exportService, ImportService importService, AuthService authService) {
        this.exportService = Objects.requireNonNull(exportService, "exportService must not be null");
        this.importService = Objects.requireNonNull(importService, "importService must not be null");
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
    }

    public Response export(Request request) {
        if (!isSuperAdmin(request)) {
            return Response.forbidden().bodyJson(Map.of("error", "Forbidden"));
        }

        try {
            byte[] zip = exportService.exportAll();
            String filename = "paprika-backup-" + LocalDate.now() + ".zip";
            return Response.ok()
                    .contentType("application/zip")
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .bodyBinary(zip);
        } catch (IOException e) {
            return Response.internalServerError().bodyJson(Map.of("error", "Export failed: " + e.getMessage()));
        }
    }

    public Response importBackup(Request request) {
        if (!isSuperAdmin(request)) {
            return Response.forbidden().bodyJson(Map.of("error", "Forbidden"));
        }

        try {
            Map<String, List<MultipartSupport.UploadedFile>> uploads = MultipartSupport.parseUploads(request);
            List<MultipartSupport.UploadedFile> files = uploads.get("file");
            if (files == null || files.isEmpty()) {
                return Response.badRequest().bodyJson(Map.of("error", "No backup file uploaded"));
            }

            ImportService.ImportResult result = importService.importAll(files.getFirst().bytes());
            return Response.ok().bodyJson(result);
        } catch (IllegalArgumentException e) {
            return Response.badRequest().bodyJson(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return Response.internalServerError().bodyJson(Map.of("error", "Import failed: " + e.getMessage()));
        }
    }

    private boolean isSuperAdmin(Request request) {
        return authService.resolveAdmin(request)
                .map(auth -> auth.isAuthenticated() && auth.isSuperAdmin())
                .orElse(false);
    }
}