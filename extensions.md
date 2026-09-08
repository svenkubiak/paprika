# Planned extensions and known gaps

This document records intentional design decisions and possible future work that is **not** implemented yet.

---

## HTTP hooks for file operations

### Current behavior

Collection file endpoints are handled by `CollectionFileController`:

- `GET /api/collections/{collection}/{id}/files/{field}`
- `GET /api/collections/{collection}/{id}/files/{field}/{fileId}`
- `DELETE /api/collections/{collection}/{id}/files/{field}`
- `DELETE /api/collections/{collection}/{id}/files/{field}/{fileId}`

These routes use `TenantContextFilter` and `ApiAuthFilter` only. They do **not** use `ApiHookFilter`.

Even if `ApiHookFilter` were added to `CollectionFileController`, it would still skip file routes. The filter contains an explicit bypass:

```java
if (isFileRoute(request)) {
    return response;
}
```

A file route is detected when the `{field}` path parameter is present.

### Semantic mapping (if hooks were enabled)

| File operation | HTTP | Closest existing hook event |
|----------------|------|----------------------------|
| Download file  | GET  | `beforeView`               |
| Delete file(s) | DELETE | `beforeUpdate` (field mutation) or `beforeDelete` |

There are no dedicated hook events such as `beforeFileDownload` or `beforeFileDelete`.

After-hooks (`afterUpdate`, `afterDelete`) for file deletion are also not triggered from `CollectionFileService`; only `CollectionController` fires after-hooks for standard CRUD.

### Security note

This is **not** an authentication gap. `ApiAuthFilter` still enforces collection rules (for example `owner` on view/delete). The integration test `CollectionFileIntegrationTest` verifies that a non-owner cannot download another user's file.

What is missing is optional **webhook integration**, not access control.

### Possible extension (not planned)

If file operations should participate in the hook system:

1. Decide whether to reuse existing events (`beforeView` / `beforeUpdate` / `beforeDelete`) or introduce file-specific events.
2. Remove or narrow the `isFileRoute()` bypass in `ApiHookFilter`, or add dedicated hook execution in `CollectionFileService`.
3. Add `ApiHookFilter` to `CollectionFileController` if hooks are resolved in the filter layer.
4. Fire after-hooks from `CollectionFileService` after successful file deletion, if parity with record updates/deletes is required.
5. Extend the admin UI hook editor and `hook-contract.ts` if new events are introduced.
6. Add integration tests for blocking hooks on file download and delete.

Until then, file download and delete remain authenticated API operations without HTTP hook side effects.
