# Collection: API Reference

`/admin/collections/:collection/api` — auto-generated, always-up-to-date REST and realtime documentation for this specific collection, built directly from its schema. There's nothing to configure here; it's a reference view.

## Endpoints

Expandable cards for each operation (`GET` list, `POST` create, `GET` by id, `PATCH` update, `DELETE`), each showing:

- The exact request shape — JSON body for scalar fields, plus a separate multipart example whenever the collection has `FILE` fields (since files can't be sent as JSON).
- A realistic example response, generated from the collection's actual field names and types.
- The error responses that operation can return (401/403/404/400/502), including validation errors shaped like `[{ "field": "...", "message": "..." }]` and hook-rejection errors (see [Hooks](/admin-ui/collection-hooks)).
- A copy-to-clipboard button for the endpoint path.

If the collection has `FILE` fields, dedicated download/delete endpoints are listed too, one pair per file field.

Every response includes the three [system fields](/concepts/collections#system-fields) (`id`, `createdAt`, `updatedAt`) alongside the schema fields — the reference notes this so it's clear they're not something you define yourself.

## Listing: paging, filtering and sorting

`GET /api/collections/{collection}` takes four optional query parameters:

```
GET /api/collections/{collection}?offset=0&limit=25&filter=<field>:eq:<value>&sort=<field>:asc|desc
```

- **`offset`** — number of records to skip; negative values count as `0`.
- **`limit`** — page size. Omitted or `<= 0` means the default of **25**; anything above the
  maximum of **100** is **clamped to 100**. A request for `limit=500` therefore answers with 100
  records, not with a silently shorter page.
- **`filter`** — exactly one field, exactly one operator (`eq`). It can only narrow what the
  collection's [rules](/admin-ui/collection-rules) already allow, never widen it.
- **`sort`** — exactly one field and one direction (`asc` or `desc`). Allowed are the collection's
  schema fields plus the system fields `id`, `createdAt` and `updatedAt`. `JSON` and `FILE` fields
  cannot be sorted. An unknown field, an unknown direction or a malformed value answers `400` —
  an invalid sort is never answered with an arbitrarily ordered page.

Without `sort` the list has a **stable default order** (insertion order). That order is what makes
paging reliable: without it, a write between two page requests can make the same record appear on
two pages or disappear from all of them.

::: tip Sorting and indexes
Sorting on a field that has no [index](/admin-ui/collection-schema#indexes) makes MongoDB sort in
memory, and that sort fails once it exceeds 32 MB. For collections that grow, add an index for the
field you sort by.
:::

## Create and update answer with the record

`POST /api/collections/{collection}` responds with `201 Created` **and the created record** in the body, `PATCH /api/collections/{collection}/{id}` with `200 OK` **and the updated record**. In both cases the body has exactly the shape `GET /api/collections/{collection}/{id}` returns for the same record: the same fields, the same file references (including their `url`), and — on the `users` collection — never `passwordHash` or `passwordSalt`.

That means the server-generated `id` is available right after a create, so a client can immediately reference the new record (for example from a second record pointing at it) without a follow-up list or read call. `DELETE` still answers without a body.

## Authentication

A separate card documents `/api/auth/register`, `/api/auth/login`, and `/api/auth/refresh` — the tenant-user auth flow that produces the `Authorization: Bearer <accessToken>` this collection's endpoints expect (unless its [Rules](/admin-ui/collection-rules) allow public access). The login/refresh response also carries `tokenType` (always `"Bearer"`) and `expiresIn` (seconds until the access token expires).

The same card documents `GET /api/auth/me`, which returns the calling tenant user's own record from just the bearer token — no id or call to `/api/collections/users` needed. It bypasses collection rules and never returns `passwordHash`, `passwordSalt`, or `role`.

Any endpoint that accepts `Authorization: Bearer <accessToken>` also accepts an
[API key](/admin-ui/auth-settings#api-keys) in the same header (`Authorization: Bearer pk_…`).
A key authenticates as the tenant user it is bound to, so the examples on this page apply
unchanged - a machine consumer just skips the login call.

The same card also lists the optional recovery endpoints: `/api/auth/password/forgot` and `/api/auth/password/reset`, plus `/api/auth/verify/request` and `/api/auth/verify/confirm`. These are off unless the tenant has [Password reset or email verification](/admin-ui/tenant-users#password-reset-and-email-verification) enabled, and the link is emailed by Paprika over the instance SMTP settings, built from the tenant's configured link URL.

## Realtime (SSE)

A third card documents the Server-Sent Events flow: connect to `GET /api/realtime` to receive a `clientId` from the `connect` event, then `POST /api/realtime/subscribe` with that `clientId` and the collections/records to watch. Once subscribed, record changes arrive as events named after the collection, carrying an `action` (`create`, `update`, or `delete`) and the record. A subscriber only receives events for records their `viewRule` would let them see — the same [rules](/admin-ui/collection-rules) that gate the regular REST API also gate realtime delivery.

## The users collection is a special case

For the tenant [`users` collection](/admin-ui/tenant-users), the reference drops the realtime card. You can't subscribe to `users` and Paprika never broadcasts account changes, so there's nothing live to document. Its REST endpoints also never return `passwordHash` or `passwordSalt`, and passwords are only ever written through the virtual `password` field, so they never show up in an example response either.
