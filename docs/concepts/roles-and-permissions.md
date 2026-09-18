# Roles & Permissions

Paprika has exactly two system roles, and they answer two different questions: **who can use the admin UI**, and **who can read/write API data**. Mixing these up is the most common source of confusion, so this page keeps them separate.

## The two roles

- **`superadmin`**: operates the Paprika instance itself. The first superadmin is created via the [initial setup link](https://github.com/svenkubiak/paprika#-initial-superadmin-setup); more can be added by invite from the [Superadmins](/admin-ui/superadmins) page. All superadmins are equal (there is no scoped or read-only admin variant), and each manages their own password and 2FA. Superadmins manage tenants, collections, rules, hooks, settings, and backups, everything under the admin UI and its underlying `/api/admin/*` and `/api/meta/*` endpoints.
- **`user`**: a regular, tenant-scoped account created for a specific tenant, either via self-registration (if enabled) or by a superadmin on the [Tenant Users page](/admin-ui/tenant-users). Tenant users have **no admin UI access whatsoever**, they only ever call `/api/collections/*` and `/api/collections/*/{id}/files/*` with a JWT.

Both the superadmin password and every tenant user password must be at least **16 characters** and are hashed with **Argon2** before being stored — plaintext passwords are never persisted, and there's no way to retrieve or display an existing password from the admin UI.

There is no intermediate "tenant admin" role. Every tenant user is a plain `user`; any differentiation in what one user can do versus another within the same tenant is handled entirely by the rule engine below, not by roles.

## Admin authentication

Superadmins sign in to the admin UI with a session cookie (optionally protected by TOTP 2FA, configured under [Settings](/admin-ui/settings)). A separate JWT-based flow (`/api/admin/token`) exists for programmatic access to the admin API. The two are mutually exclusive per request: an endpoint guarded for the admin session (`AdminAuthFilter`) rejects bearer tokens outright, and vice versa.

### Session lifetime

The admin session cookie is valid for **one hour**, and that hour is absolute rather than sliding — the cookie isn't reissued on activity, so a superadmin is signed out an hour after signing in no matter how busy the session was. When it runs out, the admin UI sends the user to the login page with a notice explaining what happened, and signing back in returns them to the page they were on. The value lives in `config.yaml` as `authentication.cookie.token.expires` (seconds) rather than in the environment, so changing it means a rebuild — and the notice on the login page names the hour explicitly, so that wording needs to change with it.

## Admin bypass on tenant data

When a superadmin has [switched into a tenant](/concepts/tenants#the-tenant-switcher), they can browse and edit that tenant's collection data through the same admin UI (Data tab) — but this does **not** go through the rule engine described below. Superadmin requests are flagged as an admin bypass and skip rule evaluation entirely, so a superadmin always has full read/write access to every tenant's data regardless of how that tenant's rules are configured.

## The rule engine: how tenant-user data access actually works

For everything a **tenant user** (not a superadmin) can do against `/api/collections/*`, access is controlled per collection and per operation by the **rule engine**, configured on each collection's [Rules tab](/admin-ui/collection-rules). There are five independent rules per collection — **list**, **view**, **create**, **update**, **delete** — each resolving to one of three modes:

| Rule value | Mode | Meaning |
|---|---|---|
| *(empty)* | `LOCKED` | Nobody can perform this operation — not even authenticated users. |
| `*` | `PUBLIC` | Anyone can perform this operation, no authentication required. |
| `auth` | `EXPRESSION` → `auth.id != null` | Any authenticated tenant user (valid JWT) can perform this operation. |
| `owner` | `EXPRESSION` → `record.<ownerField> = auth.id`, on `users`: `record.id = auth.id` | Only the tenant user referenced by the record's owner field can perform this operation - on the `users` collection, only the caller's own account (see below). |

These four correspond directly to the **presets** shown in the admin UI: *No access*, *Public*, *Signed in*, *Own records*. These are the only values the API accepts — anything else is rejected on save with `RuleParseException`. Paprika deliberately keeps the rule engine to these four presets instead of exposing a full custom expression syntax like PocketBase's.

**On the `users` collection, `owner` resolves differently:** to `record.id = auth.id`. The preset is the same, only its meaning adapts to the collection. A user record cannot hold a relation to itself, so the owner-field comparison would run against a field that does not exist and never match — which would lock every user out of their *own* account, the one case the preset is most obviously wanted for. The own record of a user is their identity, not a relation to it. Consequences: no `RELATION → users` field is needed on `users`, any stored owner field is ignored there and never written into a user record, and `owner` on the create rule can never be satisfied (there is no record yet whose id could equal the caller's — sign-up is `POST /api/auth/register`). Everything else is unchanged, including that `role` and the credential fields stay unwritable through the data plane.

**Owner rules** on every other collection need an **owner field**: a `RELATION → users` field on the collection (see [Collections](/concepts/collections)) that stores which tenant user owns each record. On create, Paprika automatically fills this field with the authenticated user's id, so client apps don't need to send it themselves. If a collection has no such relation field yet, the admin UI falls back to a plain field named `owner`.

List rules do double duty: besides gating whether the list endpoint is callable at all, they also filter *which* records come back — an `owner` list rule only returns the calling user's own records, never the whole collection.

## Worked example

A `posts` collection where anyone can read published posts, but only the author can edit or delete their own:

- **List / View**: `*` (public) — or `auth` if reads should require sign-in too.
- **Create**: `auth` — any signed-in tenant user can create a post (they become the owner automatically).
- **Update / Delete**: `owner` — only the post's author can modify or remove it.

## API keys: a second way to prove the same identity

Paprika can authenticate a **machine** as well, and it does so without inventing a new kind of
principal. An **API key** is a named, revocable credential bound to one existing tenant user:

```http
GET /api/collections/posts
Authorization: Bearer pk_iY3f…
```

A key resolves to exactly the identity an access token of that same user resolves to. Nothing on
the data path can tell the two apart - the rule engine, owner filtering, the hook envelope's
`context.auth`, and `tokenIssuers` all see the bound user, not the key. That is the whole point:
a key cannot do more (or less) than the user it belongs to.

Consequences worth spelling out:

- **A key never grants superadmin rights.** Keys can only be bound to users with the `user` role;
  a key whose user is elevated afterwards stops working. By default a key is also fully subject to
  the collection rules - hitting a locked rule gives `403`, exactly like any other authenticated
  tenant user. A key can optionally be issued as
  [rule-bypassing](#rule-bypassing-keys) for a trusted backend service, which changes that one
  thing and nothing else: no admin access, no other tenant, no superadmin, hooks still run.
- **A key is tenant-bound.** It resolves to its own tenant only; a collection of another tenant
  simply does not exist for it.
- **The plaintext exists once.** Paprika stores only a hash, so a key is shown exactly once, in
  the response that creates it.
- **Keys are revocable and named**, which a shared password is not: revoke one key without
  touching anyone else, and see per key when it was last used. A revoked key keeps its record so
  it stays auditable; deleting a key removes that record too, for housekeeping. The request log records which key
  authenticated a request (id and name, never the key).

::: danger Security assumption
**Whoever holds the key is the bound user** - including every permission that user's rules grant,
and `tokenIssuers` membership if the user has it. Bind keys to a dedicated service account with
exactly the rules that service needs, keep them server-side, and rotate them.
:::

### Rule-bypassing keys

One case the four rule presets cannot express is **"this one caller, and nobody else"**. A trusted
backend service needs to work on a tenant's data *across* user boundaries (an export job touches
records of every user), while the collections stay `No access` for clients. `Own records` says the
opposite, and `Signed in` would open the data to every end user - so neither fits, and Paprika
deliberately does not grow free-form rule expressions to close the gap.

Instead, an API key can be issued as **rule-bypassing**. Requests made with such a key are not
checked against the collection rules on the data plane (`/api/collections/**`) at all - the same
treatment an admin UI session gets on tenant data. This is Paprika's equivalent of Supabase's
`service_role` key.

The flag is set **when the key is created and never afterwards**. To change the classification of
a key, revoke it and issue a new one; a credential that is already distributed must not silently
become more powerful.

What such a key may and may not do:

| A rule-bypassing key **can** | A rule-bypassing key **cannot** |
|---|---|
| read, create, update, delete records of every collection of its tenant, regardless of the rules | reach the management API (`/api/meta/**`, `/api/admin/**`): tenant, schema, rule, hook, backup, settings and superadmin management stay closed - every request with a bearer header is refused there |
| see all records on an `Own records` collection, not only those of the bound user | touch another tenant: the key resolves to its own tenant only, an unknown collection is still a `404` |
| skip the owner field being forced on create, so it can write records on behalf of any user | become a superadmin: keys can only be bound to users with the `user` role, at creation and again at every resolve |
| be revoked (record kept) or deleted (record removed) at any time | skip the hooks: `beforeCreate` and friends run unchanged, and a blocking hook stops a bypass request like any other |
| | write credential fields or roles through `/api/collections/users`: the usual data-plane protections apply |

So this is emphatically **not a superadmin**. It is full access to the tenant's *data*, with the
tenant's *configuration* out of reach - and because hooks still fire, business guards (field
protection, immutability, approval gates) keep applying.

::: danger Security assumption
**Whoever holds a rule-bypassing key has full access to the data of that tenant - but no access
to its configuration.** Issue one such key per service, keep it in the service's environment
only, and prefer an ordinary key whenever the service can live inside the rules of its user.
:::

Managing keys: [Auth settings → API keys](/admin-ui/auth-settings#api-keys). Password login,
the admin session, and `POST /api/admin/token` are unchanged; API keys are an addition, not a
replacement.

## Trusted token issuance

Neither role covers the case of a **trusted backend** that authenticated a user somewhere else
(Apple Sign-In, SAML, a magic link) and now needs a Paprika session for that user without knowing
a password. Paprika has two mechanisms for it.

### `POST /api/auth/issue-token` (the explicit one)

An authenticated tenant user whose id is listed in its tenant's `tokenIssuers` setting can mint an
access/refresh token pair for any other user of the **same** tenant. The endpoint requires a
bearer token (it is not public), the tenant is derived from that token only, and `tokenIssuers` is
empty by default for every tenant — so nothing changes for an existing installation until a
superadmin opts in on the [Tenants](/admin-ui/tenants#editing-a-tenant) page. Full request and
response shape: [Auth settings → Trusted token issuance](/admin-ui/auth-settings#trusted-token-issuance).

The security assumption is explicit and unavoidable: **whoever is listed in `tokenIssuers` can
assume the identity of every user of that tenant.** It is the strongest permission in the system
below superadmin. Superadmins are not subject to it and cannot use the endpoint — a superadmin
token carries no unambiguous tenant-user identity.

### `beforeLogin` + `issueTokenFor` (the hook-based one)

A `beforeLogin` hook may answer `{"continue": false, "issueTokenFor": {"userId": "…"}}`, and
`POST /api/auth/login` then issues a token for that user without checking the password. This
predates the endpoint above and keeps working unchanged. The difference matters:

| | `/api/auth/issue-token` | `beforeLogin` + `issueTokenFor` |
|---|---|---|
| Endpoint | authenticated | public (`/api/auth/login`) |
| Authorization | tenant setting `tokenIssuers`, checked by Paprika | entirely up to the hook target |
| Caller identity | the caller's bearer token | none — hooks receive no credentials, and the [header allowlist](/admin-ui/collection-hooks) deliberately strips custom headers such as a one-time ticket |
| Password | not needed | a dummy value must be sent, since `password` is validated before the hook runs |

For a new integration, prefer the endpoint. The hook path remains for installations that already
depend on it.

## Where this leaves the two roles

To summarize the mental model:

- **Role** (`superadmin` vs. `user`) answers *"does this identity manage Paprika, or does it belong to one tenant's application?"*
- **Collection rules** answer *"which tenant users, if any, can perform this specific operation on this specific collection?"*

A superadmin is never subject to collection rules. A tenant user is always subject to them, on every request.
