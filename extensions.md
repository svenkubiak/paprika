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
