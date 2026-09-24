# Roles & Permissions

Paprika has exactly two system roles, and they answer two different questions: **who can use the admin UI**, and **who can read/write API data**. Mixing these up is the most common source of confusion, so this page keeps them separate.

## The two roles

- **`superadmin`**: operates the Paprika instance itself. The first superadmin is created via the [initial setup link](https://github.com/svenkubiak/paprika#-initial-superadmin-setup); more can be added by invite from the [Superadmins](/admin-ui/superadmins) page. All superadmins are equal (there is no scoped or read-only admin variant), and each manages their own password and 2FA. Superadmins manage tenants, collections, rules, hooks, settings, and backups, everything under the admin UI and its underlying `/api/admin/*` and `/api/meta/*` endpoints.
- **`user`**: a regular, tenant-scoped account created for a specific tenant, either via self-registration (if enabled) or by a superadmin on the [Tenant Users page](/admin-ui/tenant-users). Tenant users have **no admin UI access whatsoever**, they only ever call `/api/collections/*` and `/api/collections/*/{id}/files/*` with a JWT.

Both the superadmin password and every tenant user password must be at least **16 characters** and are hashed with **Argon2** before being stored — plaintext passwords are never persisted, and there's no way to retrieve or display an existing password from the admin UI.

There is no intermediate "tenant admin" role. Every tenant user is a plain `user`; any differentiation in what one user can do versus another within the same tenant is handled entirely by the rule engine below, not by roles.

## Admin authentication

Superadmins sign in to the admin UI with a session cookie (optionally protected by TOTP 2FA, configured under [Profile](/admin-ui/profile)). A separate JWT-based flow (`/api/admin/token`) exists for programmatic access to the admin API. The two are mutually exclusive per request: an endpoint guarded for the admin session (`AdminAuthFilter`) rejects bearer tokens outright, and vice versa.

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
| `group` | `MEMBERSHIP` | Only members of the group the record belongs to (see [Group membership](#group-membership-group-and-peers)). Not available on `users`. |
| `peers` | `MEMBERSHIP` | Only users who share at least one group with the caller, plus the caller's own record. **Only** available on `users`. |

These six correspond directly to the **presets** shown in the admin UI: *No access*, *Public*, *Signed in*, *Own records*, *Group members*, *Group peers*. These are the only values the API accepts — anything else is rejected on save with `RuleParseException`. Paprika deliberately keeps the rule engine to these presets instead of exposing a full custom expression syntax like PocketBase's.

**On the `users` collection, `owner` resolves differently:** to `record.id = auth.id`. The preset is the same, only its meaning adapts to the collection. A user record cannot hold a relation to itself, so the owner-field comparison would run against a field that does not exist and never match — which would lock every user out of their *own* account, the one case the preset is most obviously wanted for. The own record of a user is their identity, not a relation to it. Consequences: no `RELATION → users` field is needed on `users`, any stored owner field is ignored there and never written into a user record, and `owner` on the create rule can never be satisfied (there is no record yet whose id could equal the caller's — sign-up is `POST /api/auth/register`). Everything else is unchanged, including that `role` and the credential fields stay unwritable through the data plane.

**Owner rules** on every other collection need an **owner field**: a `RELATION → users` field on the collection (see [Collections](/concepts/collections)) that stores which tenant user owns each record. On create, Paprika automatically fills this field with the authenticated user's id, so client apps don't need to send it themselves. If a collection has no such relation field yet, the admin UI falls back to a plain field named `owner`.

List rules do double duty: besides gating whether the list endpoint is callable at all, they also filter *which* records come back — an `owner` list rule only returns the calling user's own records, never the whole collection.

## Group membership: `group` and `peers`

`owner` answers "this one user". A lot of applications need "this group of users": a team, an
organisation, a household, a shared project — data that belongs to a **group** inside one tenant,
not to a person. Without a preset for it the only option left is `auth`, and that is not access
control: every user of the application could read, change and delete the data of **every** group.

The two presets `group` and `peers` close that gap. They decide access through a **membership
record in a second collection** — the same idea as PocketBase's back-relations, a Supabase RLS
subquery or a `get()` in Firebase security rules.

### The setup

Three collections, all inside one tenant:

| Collection | Holds | Fields that matter |
|---|---|---|
| `teams` | the groups | (anything) |
| `team_members` | one record per membership | `user` → `users`, `team` → `teams` |
| `documents` | the data to protect | `team` → `teams` |

Four shapes follow from this, one per collection:

| Collection | Rule | How the group hangs off the record |
|---|---|---|
| `documents` — the data of a group | `group` | a field: `"groupRecordField": "team"` |
| `users` — the members themselves | `peers` | the record's identity; `groupRecordField` is unused |
| `teams` — the group collection itself | `group` | the record *is* the group: `"groupRecordField": "id"` |
| `team_members` — the memberships themselves | `group` | `"groupCollection": "team_members"` (itself), `"groupRecordField": "team"` |

The last one is how an application **shows the member list of a group**: who is in a team is
exactly what the membership records say, so the collection scopes itself — the caller's own
membership records name their groups, and every membership record of those groups is visible.

On `documents` the rules are set to `group` with this configuration (Rules tab, or the collection's
`rules` object):

```json
{
  "listRule": "group", "viewRule": "group", "createRule": "group",
  "updateRule": "group", "deleteRule": "group",
  "groupCollection": "team_members",
  "groupMemberField": "user",
  "groupField": "team",
  "groupRecordField": "team"
}
```

On `users` the rules are set to `peers` with the same `groupCollection`, `groupMemberField` and
`groupField` — `groupRecordField` is not used there, because a user record *is* the thing being
matched.

### The group collection itself

On `teams` the records **are** the groups, so no field points at one — the group is the record's
own `id`. Setting `groupRecordField` to `"id"` says exactly that, and every member of a team then
sees and edits their team's record without a second field duplicating its id:

```json
{
  "listRule": "group", "viewRule": "group", "createRule": "auth",
  "updateRule": "group", "deleteRule": "group",
  "groupCollection": "team_members",
  "groupMemberField": "user",
  "groupField": "team",
  "groupRecordField": "id"
}
```

**Create needs a different preset here** — `auth` or `owner`. A `group` create rule combined with
`"groupRecordField": "id"` could never be satisfied: `id` is read-only on write, so the body cannot
name the group, and nobody is a member of a group that does not exist yet. Such a configuration is
rejected with `400` when the collection is saved rather than stored as a rule that always denies.
`id` is the only system field accepted here; `createdAt` and `updatedAt` are not an identity and
stay refused.

Update and delete are granted to **every member** of the group, not only to whoever created it. An
application that wants the group record to be editable by its creator alone uses `owner` there —
that is a decision of the application, not of the preset. Creating a membership when a group is
created is the application's job as well; Paprika never writes one on its own.

Like the owner field, this is **configuration of the collection**, not part of the rule string: the
rule values stay a fixed allowlist. A `group` or `peers` rule without a complete configuration is
rejected with `400` when the collection is saved, together with the reason — it is never silently
treated as locked. The check covers that the membership collection exists, that the three field
names exist (there, respectively here), and that they are `RELATION` or `STRING` fields.

### The membership collection itself

`team_members` can use `group` with `groupCollection` pointing at **itself** and `groupRecordField`
set to its own group field (`team`). The caller's memberships determine their groups, and all
membership records of those groups come back — the member list of every team the caller is in,
including the rows of the other members:

```json
{
  "listRule": "group", "viewRule": "group",
  "groupCollection": "team_members",
  "groupMemberField": "user",
  "groupField": "team",
  "groupRecordField": "team"
}
```

This is not a circular reference: Paprika resolves the memberships **once**, past the rules (see
above), and the result only scopes the query that follows. Writes are a separate decision — who
may add or remove a membership is usually narrower than who may read the list, so `createRule`,
`updateRule` and `deleteRule` are typically locked or `owner` here.

### What each operation does

Let **my groups** be the values of `groupField` in every `groupCollection` record whose
`groupMemberField` is the caller's id.

| Operation | `group` | `peers` |
|---|---|---|
| List | filtered to records whose `groupRecordField` is one of my groups | filtered to the members of my groups, plus my own record |
| View / Delete | the record's group must be one of my groups | the record's id must be a member of my groups, or my own |
| Update | the record's **current** group must be mine, and if the body changes the group, the **new** one has to be mine too | like View |
| Create | the group **in the body** must be one of mine; a body without it is refused | always refused — there is no record yet whose identity could be checked; sign-up is `POST /api/auth/register` |

The rules that follow from this are worth stating outright:

- **No membership means no records, never all records.** A caller who is in no group gets an empty
  list, not the collection.
- **No authentication means nothing**, for both presets, in every operation.
- **The group field is never filled in for the client.** Unlike the owner field, the client says
  which group a new record belongs to and the rule checks it. Nothing is assigned automatically.
- **A revoked membership takes effect on the next request.** The lookup is made per request and
  memoized only within it; there is no expiring cache to wait out.
- **The rules of the membership collection are independent.** Paprika reads it internally for this
  decision without applying its rules — which says nothing about who may call
  `/api/collections/team_members`. Lock it, or scope it with `group` as well.
- **Realtime delivery makes the same decision.** A subscribed client never receives an event for a
  record the view rule would refuse it.
- **The admin bypass is unchanged.** An admin UI session and a
  [rule-bypassing API key](#rule-bypassing-keys) see everything, as before.

### Index the membership collection

Every request against a `group` or `peers` collection resolves the caller's memberships with a
query on `groupCollection`. Without indexes that is a collection scan per request. Add, on the
membership collection's Schema tab:

- an index on the **member field** (`user` above) — used on every request,
- an index on the **group field** (`team` above) — used by `peers` to find the other members.

### Out of scope

No nested groups, no roles within a group, no groups across tenant boundaries, and no free-form
rule expressions: the two presets are two more values on the allowlist, nothing more.

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

One case the rule presets cannot express is **"this one caller, and nobody else"**. A trusted
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
| Caller identity | the caller's bearer token | none — hooks never receive `Authorization`/`Cookie`, and the [header allowlist](/admin-ui/collection-hooks) strips every other header unless the hook explicitly lists it in `forwardHeaders` (a one-time ticket header can be opted into that way, a credential against Paprika cannot) |
| Password | not needed | a dummy value must be sent, since `password` is validated before the hook runs |

For a new integration, prefer the endpoint. The hook path remains for installations that already
depend on it.

## Where this leaves the two roles

To summarize the mental model:

- **Role** (`superadmin` vs. `user`) answers *"does this identity manage Paprika, or does it belong to one tenant's application?"*
- **Collection rules** answer *"which tenant users, if any, can perform this specific operation on this specific collection?"*

A superadmin is never subject to collection rules. A tenant user is always subject to them, on every request.
