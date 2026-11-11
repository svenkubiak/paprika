# Planned extensions and known gaps

This document records possible future work that is **not** implemented yet. Shipped behavior lives in the docs under `docs/`.

---

## HTTP hooks for file operations

Collection file routes (`GET` and `DELETE` under `/api/collections/{collection}/{id}/files/…`) use `TenantContextFilter` and `ApiAuthFilter` only. They do not use `ApiHookFilter`, and the filter would skip them anyway: `isFileRoute()` returns early when the `{field}` path parameter is present. There are no events such as `beforeFileDownload` or `beforeFileDelete`. After-hooks for file deletion are not fired from `CollectionFileService`.

This is not an authentication gap. `ApiAuthFilter` still enforces collection rules. What is missing is optional webhook integration.

### Possible extension (not planned)

1. Decide whether to reuse `beforeView` / `beforeUpdate` / `beforeDelete` or introduce file-specific events.
2. Remove or narrow the `isFileRoute()` bypass, or run hooks from `CollectionFileService`.
3. Add `ApiHookFilter` to `CollectionFileController` if hooks stay in the filter layer.
4. Fire after-hooks from `CollectionFileService` after a successful file deletion, if parity with record updates and deletes is required.
5. Extend the admin UI hook editor and `hook-contract.ts` if new events are introduced.
6. Add integration tests for blocking hooks on file download and delete.

Reusing the existing events would make current hook configurations fire for file download and delete. That is a behavior change for installations that already have hooks.

---

## Collection list filtering and sorting

Deliberately not implemented. Each item needs its own design before it can ship.

- **Composition.** `or`, brackets and nested expressions.
- **Sort extras.** Sorting by computed expressions (for example "field A, otherwise field B") and control over where records without a value sit (`nullsfirst` / `nullslast`).
- **Index tuning beyond `_id`.** Paprika does not append `_id` to indexes automatically, and the tiebreaker is always ascending, so `asc` and `desc` on the same field need two indexes. Appending `_id` automatically would change what an existing index definition means.
- **Relations.** Relation traversal and expanding relations in list responses.
- **Full-text and query shape.** A `$text` index, field projection, cursor-based pagination, aggregations, filters on realtime subscriptions, and custom rule expressions.
- **Bulk mutations.** `DELETE` / `PATCH` with a filter. `beforeUpdate` / `beforeDelete` hooks and the field guards run per record, so a bulk mutation has to say how those apply.
- **In-memory evaluation** of `FilterCondition`s (for example for realtime). The parsed form allows it, but nothing uses it yet.

---

## Image variants for file fields

Deliberately not implemented.

- **No backfill for existing files.** Changing `imageWidths` only affects new uploads. A backfill is a job of its own: progress, restartability, storage budget, and a tenant that changes the configuration while it runs.
- **No WebP or AVIF encoding.** `utils/ImageVariants` uses ImageIO, which can read WebP on recent JDKs but cannot write it. Encoding would mean a new image library. A WebP upload is stored unchanged and served as the original. The same holds for every other format ImageIO cannot write.
- **No cropping, height, format conversion or quality parameter.** The single parameter is the width, and the aspect ratio is preserved. Anything else is a second image API.

---

## Trusted token issuance (`POST /api/auth/issue-token`)

Deliberately not implemented.

- **Rate limiting.** Paprika has none for `/api/auth/*`. A limit for the whole auth surface (login, refresh, recovery, issue-token) would be new behavior on routes that answer today. The documented place for it is the reverse proxy. `issue-token` itself requires a bearer token of an allowlisted user.
- **Superadmin impersonation.** Superadmin tokens are rejected (they carry no unambiguous tenant-user identity), and there is no impersonation control in the admin UI. That would be a separate feature with its own audit story.
- **Queryable audit.** A successful issue is written to the application log (tenant id, caller id, target id) but not to a queryable collection. `RequestLogService` only records requests that carry a `collection` path parameter, so this endpoint does not appear in the request log, same as login.
- **Admin UI endpoint examples.** The per-collection API card does not list `issue-token`. It is documented on the docs site only, because it is not an app-client endpoint.

---

## API keys (`Authorization: Bearer pk_…`)

Deliberately not implemented.

- **Per-key scoping or permissions** (only these collections, read-only, and similar). The permission comes from the rules of the bound identity. Scopes would be a second policy layer next to the rule engine.
- **Superadmin keys.** A key bound to a superadmin would be a cross-tenant bypass credential. `ApiKeyService` refuses any role but `user`. Programmatic admin access stays `POST /api/admin/token`.
- **Rate limiting for the auth surface.** Still absent instance-wide. Request-level throttling remains a proxy concern.
- **Key rotation endpoint and usage analytics.** Rotation is "create new, revoke old". Usage visibility is `lastUsedAt` plus the key name in the request log. There is no per-key call count or last IP.
- **A collection allowlist for `bypassRules`.** The flag is all-or-nothing. An allowlist (for example only `invoices` and `exports`) would be one more field, one more check in `ApiAuthFilter`, and UI that stays in sync with the collection list. Worth doing if bypassing keys turn out to be common. The current answer is one key per service, bound to a user whose rules are as narrow as possible.
- **A separate audit collection for bypassing access.** A bypassing request is recognisable in the request log (key id, key name, `rulesBypassed`), subject to log retention. A non-purged audit collection of what a bypassing key read or wrote, including record ids, is a different feature with its own storage and retention story.
