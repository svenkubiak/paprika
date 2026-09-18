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

---

## Collection list filtering

### Current behavior

`GET /api/collections/{collection}` accepts an optional `filter` query parameter:

```
GET /api/collections/{collection}?filter=<field>:eq:<value>&offset=0&limit=25
```

- Exactly one field, one operator, and the operator is always `eq`.
- Parsing splits on the **first two colons only**, so the value may itself contain colons.
- The value is URL-encoded on the wire (Undertow decodes it before binding).
- An absent or empty `filter` behaves exactly as before — no filter is applied.

The client filter is combined with the authorization filter via `Filters.and` in
`CollectionRecordService.list` and can only **narrow** a list, never replace or widen it. The
rule evaluation, `AuthorizationDecision`, and the fail-closed behavior of the list path are
untouched. Both the returned page and `total` use the same effective filter.

Parsing and type conversion live in `rules/ListFilterParser`:

- Allowed fields are the collection's schema fields plus the indexable system fields
  (`id`, `createdAt`, `updatedAt`). An unknown field is a `400`.
- Values are converted to the field's BSON type (`BOOLEAN`, `NUMBER`, string types). `DATE`,
  `TIME`, `DATETIME` and the timestamp system fields are stored as ISO strings and compared as
  strings. `JSON` and `FILE` are not filterable and return `400`.

An index is **not** required. Without one the filter costs a collection scan, but the rule
filter already bounds the candidate set — this is an operational tuning question, not a reason
to reject the request.

### Deliberately not implemented

The following are intentionally out of scope for now and would be the natural follow-ups:

- **Bulk mutations** — `DELETE`/`PATCH` with a filter. `beforeUpdate`/`beforeDelete` hooks and
  the field guards run per record, so a bulk mutation has to clarify how those apply before it
  can exist.
- **More operators and composition** — anything beyond `eq`, plus `and`/`or`, bracketing,
  ordering comparisons, full-text search, sorting, and relation traversal.
- **Admin UI search** — the data view could grow a search box on top of this parameter
  (`admin-ui/src/lib/api.ts` currently builds only `?offset=&limit=`).
