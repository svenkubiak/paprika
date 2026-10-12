# Collection: API Reference

`/admin/collections/:collection/api` — auto-generated, always-up-to-date REST documentation for this specific collection, built directly from its schema. There's nothing to configure here; it's a reference view.

A box at the top gives what every call needs: the **base URL**, the `Authorization: Bearer <token>` header, and a line on the system fields. Realtime is not documented on the page itself; the box links to [Realtime (SSE)](#realtime-sse) below.

## Endpoints

One row per operation (`GET` list, `POST` create, `GET` by id, `PATCH` update, `DELETE`). Each row shows its method and path, a copy button, and a badge with the rule that guards it — **Public**, **Signed in**, **Own records** and so on, linking to the [Rules](/admin-ui/collection-rules) tab. That badge answers whether a call needs a token at all. Expanding a row shows:

- The exact request shape — JSON body for scalar fields, plus a separate multipart example whenever the collection has `FILE` fields (since files can't be sent as JSON).
- A realistic example response, generated from the collection's actual field names and types.

The error responses are listed once, in an **Errors** table at the end of the page, instead of under every endpoint: 401/403/404/400/502, including validation errors shaped like `[{ "field": "...", "message": "..." }]` and hook-rejection errors (see [Hooks](/admin-ui/collection-hooks)).

If the collection has `FILE` fields, dedicated download/delete endpoints are listed too, one pair per file field.

Every response includes the three [system fields](/concepts/collections#system-fields) (`id`, `createdAt`, `updatedAt`) alongside the schema fields — the reference notes this so it's clear they're not something you define yourself.

## Listing: paging, filtering and sorting

`GET /api/collections/{collection}` takes these optional query parameters:

```
GET /api/collections/{collection}?offset=0&limit=25&filter[status][eq]=published&search=<text>&sort=<field>:asc|desc
```

- **`offset`** — number of records to skip; negative values count as `0`.
- **`limit`** — page size. Omitted or `<= 0` means the default of **25**; anything above the
  maximum of **100** is **clamped to 100**. A request for `limit=500` therefore answers with 100
  records, not with a silently shorter page.
- **`filter`** — conditions on fields, see [Filtering](#filtering) below.
- **`search`** — free text matched like `contains` against the `id` and every text field at
  once; a record matches if any of them contains the text. This is what the search box of the
  data view uses. Like `filter` it only narrows, and the two can be combined. `total` counts the
  matching records.
- **`sort`** — up to three fields, see [Sorting](#sorting) below.

`total` always counts with the same conditions as the page, so it never reveals how many records
there would be without the filter.

### Filtering

A condition names a field, an operator and a value. There are two ways to write it, and they can
be combined:

```
GET /api/collections/posts?filter=status:eq:published
GET /api/collections/posts?filter[status][in]=["draft","review"]&filter[publishedAt][gte]=2026-01-01T00:00:00Z&filter[cover][notnull]=
```

- **`filter=<field>:<operator>:<value>`** holds exactly one condition. It is split on the first
  two colons only, so the value may contain colons. `null` and `notnull` take no value:
  `filter=cover:null`.
- **`filter[<field>][<operator>]=<value>`** can be repeated for as many conditions as needed. `null`
  and `notnull` take an empty value: `filter[cover][notnull]=`.

All conditions are combined with **and**, with each other and with the collection's
[rules](/admin-ui/collection-rules): a filter can only narrow what the rules already allow, never
widen it. There is no `or` and no grouping.

Each query parameter may appear only once — Paprika rejects a request that repeats one with `400`,
rather than silently using one of the values. So every `filter[<field>][<operator>]` combination can
be used once per request. Two `eq` conditions on the same field could never both be true anyway; a
range is `gte` plus `lt`, and several allowed values are one `in`.

| Operator | Field types | Value | Matches |
|---|---|---|---|
| `eq`, `ne` | every type except `JSON` and `FILE` | one value | equal / not equal |
| `gt`, `gte`, `lt`, `lte` | `NUMBER`, `DATE`, `TIME`, `DATETIME`, `createdAt`, `updatedAt` | one value | greater / greater or equal / less / less or equal |
| `in`, `nin` | every type except `JSON` and `FILE` | JSON array | one of / none of the values |
| `contains` | `STRING`, `EMAIL`, `URL`, `SELECT`, `RELATION` | one value | part of the text, ignoring case |
| `startsWith` | `STRING`, `EMAIL`, `URL` | one value | the start of the text, ignoring case |
| `null`, `notnull` | every type except `JSON`, including `FILE` | none | has no value / has a value |

Besides the schema fields, `id`, `createdAt` and `updatedAt` can be filtered. `JSON` fields cannot
be filtered at all, and `FILE` fields only with `null`/`notnull` ("has a cover image"). Order
comparisons are not offered on text fields: as strings `"10"` sorts before `"9"`, which would
surprise more than it helps.

**Values** are checked against the field type, and a value that does not fit is a `400`, not an
empty list:

- `NUMBER` must be a number; `BOOLEAN` is `true` or `false`.
- `SELECT` values must be among the field's allowed values, so a typo is reported.
- `DATE`, `TIME`, `DATETIME`, `createdAt` and `updatedAt` take any ISO value and are compared in
  UTC, so `filter[publishedAt][gte]=2026-01-01T00:00:00+02:00` finds records from
  `2025-12-31T22:00:00Z` on.
- Text and `RELATION` values are taken literally. `contains` and `startsWith` have no pattern
  syntax: `.`, `*` or `(` match themselves.
- `in`/`nin` take a JSON array, so commas inside values need no escaping and values keep their
  type: `filter[price][in]=[10,20]`, `filter[tags][in]=["a","b"]`. Entries must have the field's
  JSON type — a number field takes `[10]`, not `["10"]`.

**No value** means: the field is missing, `null`, an empty string or an empty array. That is what
`null` matches and `notnull` excludes. **`ne` and `nin` also match records without a value**:
`filter[status][ne]=archived` returns records without a status as well. To exclude them, add
`notnull`: `filter[status][ne]=archived&filter[status][notnull]=`. This is deliberate — without an
`or`, it is the only rule that leaves both readings expressible. Systems differ here (SQL and
PostgREST leave out `NULL` on "not equal", MongoDB's `$ne` includes missing fields), so do not
assume one or the other.

**Multi-valued fields** (`SELECT` and `RELATION` with *max select* above 1) hold an array, and every
operator looks at the single entries: `eq` means "contains the value", `ne` "does not contain it",
`in` "contains any of them", `nin` "contains none of them", and `contains` matches part of any
entry.

**Limits**: at most 20 conditions per request, values of at most 500 characters and at most 100
entries in an `in`/`nin` list. Each is a `400`.

**URL encoding**: values must be URL-encoded. In particular a `+` (as in a time offset) has to be
sent as `%2B`, otherwise it arrives as a space. curl does this for you:

```bash
curl -G 'https://<host>/api/collections/posts' \
  --data-urlencode 'filter[publishedAt][gte]=2026-01-01T00:00:00+02:00' \
  --data-urlencode 'filter[status][in]=["draft","review"]'
```

The brackets may be sent as they are or encoded (`filter%5Bstatus%5D%5Beq%5D=…`).

**Errors** are a `400` whose message names the condition and the problem, for example:

```json
{ "error": "filter[price][gte]='abc': value for number field 'price' is not a number" }
```

On the `users` collection, `password` and the stored credentials (`passwordHash`, `passwordSalt`,
the reset and verification tokens) are never returned, so they can neither be filtered nor sorted.
Every other field of `users` works like on any other collection.

### Sorting

```
GET /api/collections/posts?sort=status:asc,publishedAt:desc
```

`sort` takes up to three `<field>:asc|desc` keys separated by commas; the single-field form
`sort=title:asc` is the same thing with one key. The fields are those that can be filtered, except
`FILE`. An unknown field, an unknown direction, a field used twice or a malformed value answers
`400` — an invalid sort is never answered with an arbitrarily ordered page.

Without `sort` the list has a **stable default order** (insertion order). That order is what makes
paging reliable: without it, a write between two page requests can make the same record appear on
two pages or disappear from all of them. With `sort`, records that are equal in every sort field
keep that insertion order among themselves, in both directions, so paging over a field like a
status or a date is just as reliable.

**Records without a value** sort the way MongoDB orders them, for every sort key including the
second and third: with `asc` an empty array comes first, then missing and `null` (which count as
equal), then the values — and an empty string sorts among the strings, before `"a"`. `desc`
reverses this, so they come last. A multi-valued field sorts by its smallest entry with `asc` and
by its largest with `desc`. "No value" in a filter and the position in a sort are therefore not the
same thing for `""` and `[]`.

::: tip Sorting and indexes
An [index](/admin-ui/collection-schema#indexes) only speeds up a sort if it matches it exactly,
**including the insertion order that breaks ties**: the sort fields in the same order and
direction, followed by `_id` ascending. For `sort=status:asc` that is an index on
`status` ascending, `_id` ascending; for `sort=status:desc,title:asc` one on `status` descending,
`title` ascending, `_id` ascending. An index on `status` alone helps filtering by `status`, but not
sorting by it.

Without a matching index MongoDB reads and sorts every matching record for each page. That is fine
for small collections; for large ones a list that takes longer than 5 seconds answers `400` with
`query too expensive, add an index or narrow the filter`.
:::

## Create and update answer with the record

`POST /api/collections/{collection}` responds with `201 Created` **and the created record** in the body, `PATCH /api/collections/{collection}/{id}` with `200 OK` **and the updated record**. In both cases the body has exactly the shape `GET /api/collections/{collection}/{id}` returns for the same record: the same fields, the same file references (including their `url`), and — on the `users` collection — never `passwordHash` or `passwordSalt`.

That means the server-generated `id` is available right after a create, so a client can immediately reference the new record (for example from a second record pointing at it) without a follow-up list or read call. `DELETE` still answers without a body.

## File routes and global hooks

The file routes (`GET`/`DELETE /api/collections/{collection}/{id}/files/{field}[/{fileId}]`) are
gated by the collection's [rules](/admin-ui/collection-rules) like every other route — a download
is checked as `view`, a file deletion as `update`.

A tenant-wide [`beforeRequest` hook](/admin-ui/global-hooks) only runs before them when it has
**Also guard file routes** switched on. That is off by default, and it costs one hook roundtrip
per file request; see [Global Hooks → File routes](/admin-ui/global-hooks#file-routes-off-by-default).

## File downloads are cacheable

A download (`GET /api/collections/{collection}/{id}/files/{field}[/{fileId}]`) answers with a strong
`ETag` built from the id of the stored file. Storing a file always mints a new id, so a replaced
file never reuses the validator of the file it replaced. A request repeating that value in
`If-None-Match` is answered with `304 Not Modified` and no body.

The accompanying `Cache-Control: private, max-age=300, immutable` is deliberately `private`:
access to a file is decided by the collection's [rules](/admin-ui/collection-rules), so a shared
cache in front of Paprika must never serve a stored copy to a different caller. The five minutes
are short on purpose — the download URL of a single-file field carries no file id, so the same URL
delivers different bytes once the field is replaced.

## Image variants: `?width=`

If a `FILE` field is configured with [image widths](/admin-ui/collection-schema#image-widths-on-a-file-field), every uploaded JPEG, PNG or GIF is stored together with a downscaled copy per configured width. A client asks for one with `width`:

```
GET /api/collections/{collection}/{id}/files/{field}[/{fileId}]?width=320
```

- The exact width is delivered when it exists. Variants are produced in the background after
  the upload, so right after it the fallback below may still answer.
- Otherwise the **next larger** variant is delivered, and the original if there is none. The
  fallback never goes downwards: a too small image is a visible quality defect, a too large one
  only costs bandwidth.
- The response always names what it delivered in **`X-Image-Width`** — either the width in pixels
  or `original`. Without it there would be no way to tell a working configuration from one that
  never produced a variant.
- A `width` that is not a positive number answers `400`. A width the field does not have
  configured is **not** an error — that is what the fallback is for.
- On a non-image, or on a format that cannot be scaled, the original is delivered with
  `X-Image-Width: original`.
- The `ETag` includes the delivered variant, so a cache cannot answer one width with another.

Variants are created when the file is uploaded, never on the fly: the cost falls once on the
write, and no caller can keep the server busy by asking for arbitrary widths. Changing the
configuration therefore only affects **new** uploads; existing files keep falling back.

## Authentication

On the [`users` collection](/admin-ui/tenant-users) only, an **Authentication** card documents `/api/auth/register`, `/api/auth/login`, `/api/auth/refresh` and `/api/auth/logout` — the tenant-user auth flow that produces the `Authorization: Bearer <accessToken>` this collection's endpoints expect (unless its [Rules](/admin-ui/collection-rules) allow public access). The login/refresh response also carries `tokenType` (always `"Bearer"`) and `expiresIn` (seconds until the access token expires).

The same card documents `GET /api/auth/me`, which returns the calling tenant user's own record from just the bearer token — no id or call to `/api/collections/users` needed. It bypasses collection rules and never returns `passwordHash`, `passwordSalt`, or `role`.

Any endpoint that accepts `Authorization: Bearer <accessToken>` also accepts an
[API key](/admin-ui/auth-settings#api-keys) in the same header (`Authorization: Bearer pk_…`).
A key authenticates as the tenant user it is bound to, so the examples on this page apply
unchanged - a machine consumer just skips the login call.

The same card also lists the optional recovery endpoints: `/api/auth/password/forgot` and `/api/auth/password/reset`, plus `/api/auth/verify/request` and `/api/auth/verify/confirm`. These are off unless the tenant has [Password reset or email verification](/admin-ui/tenant-users#password-reset-and-email-verification) enabled, and the link is emailed by Paprika over the instance SMTP settings, built from the tenant's configured link URL.

## Realtime (SSE)

The API tab links here rather than documenting the Server-Sent Events flow itself: connect to `GET /api/realtime` to receive a `clientId` from the `connect` event, then `POST /api/realtime/subscribe` with that `clientId` and the collections/records to watch. Once subscribed, record changes arrive as events named after the collection, carrying an `action` (`create`, `update`, or `delete`) and the record. A subscriber only receives events for records their `viewRule` would let them see — the same [rules](/admin-ui/collection-rules) that gate the regular REST API also gate realtime delivery.

Subscribing fixes the identity of a stream. The `viewRule` is evaluated again for every single event, but always for the user that subscribed: the access token is checked once, at `subscribe`, and not again for the life of the connection. A stream therefore lives only as long as the token it was subscribed with: once that token expires, Paprika closes the stream within 30 seconds. To keep a stream, call `POST /api/realtime/subscribe` again with the same `clientId` and the refreshed token whenever you refresh it; that renews the stream to the new token's expiry. A stream subscribed with an API key lives until the key's `expiresAt`, or for as long as the connection stays open if the key has none, and is closed as soon as the key is revoked or deleted or its allowed source ranges change; subscribe again to have the new ranges checked. Paprika also closes a user's open streams by itself when the user is deleted, whether through the admin UI or `DELETE /api/collections/users/{id}`, when a superadmin is removed, and whenever their tokens are revoked: on a password reset, a password or email change, and on logout (see [Sessions and token lifetime](/concepts/tenants#sessions-and-token-lifetime)).

## The users collection is a special case

For the tenant [`users` collection](/admin-ui/tenant-users), the reference shows the **Authentication** card and no realtime link. You can't subscribe to `users` and Paprika never broadcasts account changes, so there's nothing live to document. Its REST endpoints also never return `passwordHash` or `passwordSalt`, and passwords are only ever written through the virtual `password` field, so they never show up in an example response either.
