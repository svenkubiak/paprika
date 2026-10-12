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

## Collection list filtering and sorting

### Current behavior

`GET /api/collections/{collection}` accepts filter conditions in two forms, combined with AND:

```
GET /api/collections/{collection}?filter=<field>:<operator>:<value>
GET /api/collections/{collection}?filter[<field>][<operator>]=<value>&filter[<field>][<operator>]=<value>
```

- The plain `filter` holds one condition and is split on the **first two colons only**, so the
  value may contain colons; `null`/`notnull` take no value (`cover:null`). The old
  `<field>:eq:<value>` and `<field>:contains:<value>` stay valid unchanged.
- `filter[<field>][<operator>]` keys may be repeated with different fields/operators. A
  repeatable `filter=…&filter=…` is not possible: mangoo rejects any request that repeats a
  query key (HTTP parameter pollution guard in `DispatcherHandler`) and binds only one value. The
  bracket keys are read from `Request.getQueryParameter()` in `CollectionRecordService.list`;
  a key that is repeated is therefore a `400` from mangoo, never a silently dropped condition.
- Values are URL-decoded by Undertow before they reach the parser; they are not decoded again.
- Operators: `eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `in`, `nin`, `contains`, `startsWith`, `null`,
  `notnull`. Order comparisons only on `NUMBER`, `DATE`, `TIME`, `DATETIME` and the timestamp
  system fields; `contains` on `STRING`, `EMAIL`, `URL`, `SELECT`, `RELATION`; `startsWith` on
  `STRING`, `EMAIL`, `URL`. `JSON` is not filterable, `FILE` only with `null`/`notnull`.
- `in`/`nin` take a JSON array whose entries must have the field's JSON type.
- "No value" is missing, `null`, `""` or `[]`. `null` matches it, `notnull` excludes it, and
  `ne`/`nin` include it (MongoDB's native `$ne`/`$nin` semantics); `ne` plus `notnull` gives the
  SQL reading.
- On arrays (multi-valued `SELECT`/`RELATION`) every operator applies per element.
- Limits (`ListFilterParser.MAX_CONDITIONS`, `MAX_VALUE_LENGTH`, `MAX_LIST_ENTRIES`): 20
  conditions, 500 characters per value or list entry, 100 list entries.

The client filter is combined with the authorization filter via `Filters.and` in
`CollectionRecordService.list` and can only **narrow** a list, never replace or widen it. The
rule evaluation, `AuthorizationDecision`, and the fail-closed behavior of the list path are
untouched. Both the returned page and `total` use the same effective filter.

Parsing, validation and conversion live in `rules/ListFilterParser`. Each condition is parsed
into a `FilterCondition(field, operator, value)` with an already typed value, validated against
the `CollectionDefinition`, and only then turned into `Bson` (`ListFilterParser.toBson`), so the
conditions themselves do not depend on MongoDB:

- Allowed fields (`rules/ListFields`, shared with the sort) are the schema fields plus `id`,
  `createdAt`, `updatedAt`. On `users`, `password` and the credential fields
  (`UserRecordUtils.CREDENTIAL_FIELDS`) are rejected: a field that is never returned must not be
  probed through a filter or an ordering either.
- Values are converted to the field's BSON type. `DATE`, `TIME`, `DATETIME` and the timestamp
  system fields are stored as normalized ISO strings (`utils/Timestamps`), and filter values are
  normalized the same way, so `+02:00` and `Z` compare correctly. `SELECT` values must be among
  the allowed values. A value that does not fit is a `400` naming the condition, e.g.
  `filter[price][gte]='abc': value for number field 'price' is not a number`.
- `contains` and `startsWith` build the regex with `Pattern.quote` only, never from client regex
  syntax.

An index is **not** required. Without one the filter costs a collection scan, but the rule
filter already bounds the candidate set. `contains` is an unanchored regex and never uses an
index; `startsWith` is case-insensitive and cannot use one efficiently either.

`find` and `countDocuments` run with `maxTime` (`CollectionRecordService.LIST_MAX_TIME`, 5 s).
Hitting it, or MongoDB refusing a sort over its memory limit, answers `400` with
`query too expensive, add an index or narrow the filter` instead of a `500`.

A separate `search=<text>` parameter backs the search box of the admin data views
(`ListFilterParser.search`): `contains` against `id` and every text field, ORed together. The
OR is built on the server and not exposed in the filter grammar. `search` is ANDed with the rule
filter and the client filter like any other narrowing. The tenant users list
(`GET /api/meta/tenants/{id}/users?search=`) uses the same matcher over the users schema, so both
admin views find the same records.

The list also accepts an optional `sort` parameter, parsed by `rules/ListSortParser`:

```
GET /api/collections/{collection}?sort=<field>:asc|desc[,<field>:asc|desc]
```

- Up to three keys (`ListSortParser.MAX_FIELDS`); no `-field` shorthand. The same fields as the
  filter, except `FILE`. An unknown field, a field used twice, an unknown direction, or a
  malformed value is a `400` — an invalid sort is never answered with an arbitrarily ordered page.
- Without `sort`, the list is ordered by `_id`, which is always indexed and insertion-ordered.
  That default is what makes paging stable: an unordered cursor can repeat or drop a record
  once a write lands between two page requests.
- With `sort`, `_id` ascending is always appended as the last key. Equal sort values have no
  defined order otherwise, so paging over a non-unique field (a status, a date, `createdAt`
  within the same millisecond) could repeat or skip records. `_id` is used rather than `id`
  because `id` can be set by clients and has no unique index.
- Records without a value sort in MongoDB's order, for every key: with `asc` `[]` first, then
  missing and `null` (equal, so `_id` decides), then the values, with `""` among the strings.
  `desc` reverses it. Arrays sort by their smallest element with `asc` and their largest with
  `desc`. This differs from the filter's "no value" for `""` and `[]`; it is documented, not
  normalized.
- Because of the `_id` tiebreaker, an index on the sort field alone cannot serve the sort
  (verified with `explain()`: `{status: 1}` leaves a `SORT` stage for `{status: 1, _id: 1}`, and
  every matching record is read per page). An index serves it only with the sort keys in order
  and direction followed by `_id` ascending. Index definitions therefore accept `_id`
  (`TenantCollectionService.validateIndex`), except as the only field and in a unique index.

`limit` is **clamped** to the maximum of 100 rather than falling back to the default of 25: a
request for more than the maximum asks for as much as possible, and answering it with a much
shorter page loses records without the client being able to notice. `limit <= 0` still means
"not specified" and yields the default.

### Deliberately not implemented

The following are intentionally out of scope for now and would be the natural follow-ups:

- **Composition** — `or`, brackets and nested expressions. If `or` across different fields turns
  out to be needed, it gets its own design.
- **Sort extras** — sorting by computed expressions (e.g. "field A, otherwise field B") and
  control over the position of records without a value (`nullsfirst`/`nullslast`).
- **Index tuning beyond `_id`** — Paprika does not append `_id` to indexes automatically, and the
  tiebreaker is always ascending, so `asc` and `desc` on the same field need two indexes.
- **Relations** — relation traversal and expanding relations.
- **Full-text search** with a `$text` index, field projection, cursor-based pagination,
  aggregations, filters on realtime subscriptions, and custom rule expressions.
- **Bulk mutations** — `DELETE`/`PATCH` with a filter. `beforeUpdate`/`beforeDelete` hooks and
  the field guards run per record, so a bulk mutation has to clarify how those apply before it
  can exist.
- **In-memory evaluation** of `FilterCondition`s (e.g. for realtime) — the parsed form allows it,
  but nothing uses it yet.

## Image variants for file fields

### Current behavior

A `FILE` field can carry an `imageWidths` option (`models/FieldOptions`, at most
`MAX_IMAGE_WIDTHS` = 4 widths of at most `MAX_IMAGE_WIDTH` = 4096 px). On upload,
`FileFieldService.storeUpload` writes one downscaled copy per width next to the original; the
storage key is derived from the file id (`FileStorageService.variantKey`), so no second index is
needed to find, read or delete them. A download picks a variant with `?width=`, falling back to
the next larger variant and then to the original, and always names the delivered width in
`X-Image-Width`.

### Deliberately not implemented

- **No backfill for existing files.** Changing `imageWidths` only affects new uploads. Files
  stored before the change keep answering through the fallback of the download path. A backfill
  is a job with its own concerns — progress, restartability, storage budget, and what to do with
  a tenant that changes the configuration while it runs — and does not belong in the upload path.
- **No WebP (or AVIF) encoding.** `utils/ImageVariants` uses ImageIO, which can read WebP on
  recent JDKs but cannot write it. Encoding would mean a new image library, which is out of
  scope; a WebP upload is therefore stored unchanged and always served as the original. The same
  holds for every other format ImageIO cannot write.
- **No cropping, height, format conversion or quality parameter.** The single parameter is the
  width, and the aspect ratio is preserved. Anything else is a second image API.

## Trusted token issuance (`POST /api/auth/issue-token`)

A tenant user listed in the tenant's `tokenIssuers` setting can mint a session for any other user
of the same tenant (`dtos/IssueTokenDto`, `AuthController.issueToken`,
`TenantUserService.resolveTokenIssue`, `results/TokenIssueResult`). Documented in
`docs/admin-ui/auth-settings.md` and `docs/concepts/roles-and-permissions.md`.

### Deliberately not implemented

- **Rate limiting / brute-force braking.** Paprika has none for `/api/auth/*` at all today, so
  the new endpoint got none either instead of inventing a one-off mechanism for a single route.
  A rate limit for the whole auth surface (login, refresh, recovery, issue-token) is the natural
  follow-up; `/api/auth/issue-token` is at least not publicly reachable, since it requires a
  bearer token of an allowlisted user.
- **Superadmin impersonation.** Superadmin tokens are rejected by the endpoint (they carry no
  unambiguous tenant-user identity), and there is no impersonation button in the admin UI. That
  would be a separate feature with its own audit story.
- **Audit trail.** A successful issue is written to the application log (tenant id, caller id,
  target id — no usernames or email addresses) but not to a queryable audit collection.
  `RequestLogService` only records requests that carry a `collection` path parameter, so like
  `/api/auth/login` this endpoint does not show up in the request log.
- **Admin UI endpoint examples.** The per-collection API card documents `register`, `login`,
  `refresh`, `me`, and the recovery endpoints; `issue-token` is documented in the docs site only,
  since it is not an app-client endpoint.

## API keys (`Authorization: Bearer pk_…`)

A named, revocable credential bound to one tenant user (`utils/ApiKeys`, `services/ApiKeyService`,
`models/ApiKeyDefinition`, `dtos/ApiKeyDto`, the `/api/meta/tenants/{tenantId}/api-keys` routes,
`admin-ui/src/components/ApiKeysManager.vue`). It resolves in `AuthService.resolveBearer` to the
same `AuthContext` an access token of that user produces, so the data path is untouched.

Two decisions worth knowing:

- **Stored as SHA-256, not Argon2.** A key is 40 characters of URL-safe `SecureRandom` output
  (~240 bits), so there is nothing to brute force from the stored hash, while an Argon2 verify on
  every single request would sit on the hottest path of the API and let an attacker burn CPU with
  bogus keys. Comparison is constant time. Reasoning also lives in `utils/ApiKeys`.
- **Records live in the system database**, with `tenantId` on each record, because the presented
  key is the only input available at resolve time - no tenant is known yet, and scanning every
  tenant database per request is not viable. The resolved context is tenant-bound and the
  management endpoints only ever see keys of the tenant in their path.

### Deliberately not implemented

- **Per-key scoping or permissions** (only these collections, read-only, …). The permission comes
  from the rules of the bound identity; a key is a way to prove an identity, not a second
  authorization system. If a service needs less, bind it to a user whose rules give less. Adding
  scopes would mean a second policy layer next to the rule engine and a `context.auth` that no
  longer describes a user.
- **Superadmin keys.** A key bound to a superadmin would be a cross-tenant bypass credential;
  `ApiKeyService` refuses any role but `user`, at creation and again at resolve time. Programmatic
  admin access stays `POST /api/admin/token`.
- **Rate limiting for the auth surface.** Still absent instance-wide (see the note on
  `/api/auth/issue-token` above). A key is at least not brute-forcible in practice - unlike a
  password it carries ~240 bits of entropy - but request-level throttling remains a proxy concern
  and a natural follow-up.
- **Key rotation endpoint / usage analytics.** Rotation is "create new, revoke old" by hand, and
  usage visibility is `lastUsedAt` plus the key name in the request log; there is no per-key call
  count or last-IP.

### Rule-bypassing keys: deliberately not implemented

The `bypassRules` flag is all-or-nothing on the data plane. Two refinements were considered and
left out:

- **A collection allowlist per key** (`bypassRules` limited to e.g. `invoices`, `exports`). It
  would document a service's reach in the key itself and shrink the blast radius of a leak, at the
  cost of one more field, one more check in `ApiAuthFilter`, and a UI that has to stay in sync
  with the collection list. Worth doing if bypassing keys turn out to be common; the current
  answer is "one key per service, bound to a user whose rules are as narrow as possible".
- **An audit trail of bypassing access.** Today a bypassing request is recognisable in the
  request log (key id, key name, `rulesBypassed`), which is per request and subject to the log
  retention setting. A separate, non-purged audit collection of what a bypassing key read or
  wrote - including record ids - is a different feature with its own storage and retention story.
