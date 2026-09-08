# Roles & Permissions

Paprika has exactly two system roles, and they answer two different questions: **who can use the admin UI**, and **who can read/write API data**. Mixing these up is the most common source of confusion, so this page keeps them separate.

## The two roles

- **`superadmin`**: operates the Paprika instance itself. The first superadmin is created via the [initial setup link](https://github.com/svenkubiak/paprika#-initial-superadmin-setup); more can be added by invite from the [Superadmins](/admin-ui/superadmins) page. All superadmins are equal (there is no scoped or read-only admin variant), and each manages their own password and 2FA. Superadmins manage tenants, collections, rules, hooks, settings, and backups, everything under the admin UI and its underlying `/api/admin/*` and `/api/meta/*` endpoints.
- **`user`**: a regular, tenant-scoped account created for a specific tenant, either via self-registration (if enabled) or by a superadmin on the [Tenant Users page](/admin-ui/tenant-users). Tenant users have **no admin UI access whatsoever**, they only ever call `/api/collections/*` and `/api/collections/*/{id}/files/*` with a JWT.

Both the superadmin password and every tenant user password must be at least **16 characters** and are hashed with **Argon2** before being stored — plaintext passwords are never persisted, and there's no way to retrieve or display an existing password from the admin UI.

There is no intermediate "tenant admin" role. Every tenant user is a plain `user`; any differentiation in what one user can do versus another within the same tenant is handled entirely by the rule engine below, not by roles.

## Admin authentication

Superadmins sign in to the admin UI with a session cookie (optionally protected by TOTP 2FA, configured under [Settings](/admin-ui/settings)). A separate JWT-based flow (`/api/admin/token`) exists for programmatic access to the admin API. The two are mutually exclusive per request: an endpoint guarded for the admin session (`AdminAuthFilter`) rejects bearer tokens outright, and vice versa.

## Admin bypass on tenant data

When a superadmin has [switched into a tenant](/concepts/tenants#the-tenant-switcher), they can browse and edit that tenant's collection data through the same admin UI (Data tab) — but this does **not** go through the rule engine described below. Superadmin requests are flagged as an admin bypass and skip rule evaluation entirely, so a superadmin always has full read/write access to every tenant's data regardless of how that tenant's rules are configured.

## The rule engine: how tenant-user data access actually works

For everything a **tenant user** (not a superadmin) can do against `/api/collections/*`, access is controlled per collection and per operation by the **rule engine**, configured on each collection's [Rules tab](/admin-ui/collection-rules). There are five independent rules per collection — **list**, **view**, **create**, **update**, **delete** — each resolving to one of three modes:

| Rule value | Mode | Meaning |
|---|---|---|
| *(empty)* | `LOCKED` | Nobody can perform this operation — not even authenticated users. |
| `*` | `PUBLIC` | Anyone can perform this operation, no authentication required. |
| `auth` | `EXPRESSION` → `auth.id != null` | Any authenticated tenant user (valid JWT) can perform this operation. |
| `owner` | `EXPRESSION` → `record.<ownerField> = auth.id` | Only the tenant user referenced by the record's owner field can perform this operation. |

These four correspond directly to the **presets** shown in the admin UI: *No access*, *Public*, *Signed in*, *Own records*. A rule can also be a raw custom expression instead of one of these four keywords, evaluated by the same rule parser.

**Owner rules** need an **owner field**: a `RELATION → users` field on the collection (see [Collections](/concepts/collections)) that stores which tenant user owns each record. On create, Paprika automatically fills this field with the authenticated user's id, so client apps don't need to send it themselves. If a collection has no such relation field yet, the admin UI falls back to a plain field named `owner`.

List rules do double duty: besides gating whether the list endpoint is callable at all, they also filter *which* records come back — an `owner` list rule only returns the calling user's own records, never the whole collection.

## Custom expressions

`*`, `auth`, and `owner` are shortcuts for the three most common cases, but any rule field actually accepts a small expression language, evaluated per-request and (for list rules) also translated into a MongoDB filter. This is what the admin UI's [Rules tab](/admin-ui/collection-rules) falls back to whenever you need something more specific than the four presets.

**Operators**: `=`, `!=`, `>`, `>=`, `<`, `<=`, `in (a, b, c)`, boolean `and` / `or` / `not`, and parentheses for grouping.

**Literals**: numbers, quoted strings (`"..."` or `'...'`), `true`, `false`, `null`.

**Field access**, via a `prefix.name` identifier (the prefix can be omitted for record fields):

| Prefix | Resolves to | Available on |
|---|---|---|
| `record.<field>` or bare `<field>` | A field on the record being read/written | All rules |
| `auth.id`, `auth.role`, `auth.tenantId` | The calling tenant user's JWT claims | All rules |
| `body.<field>` | A field from the raw request body being submitted | Mainly useful on `createRule`, where the record doesn't exist yet |

**Examples:**

```
record.status = "published"
record.age >= 18 and record.age <= 65
record.category in ("news", "sports", "tech")
auth.id != null and record.archived != true
not (record.visibility = "private")
```

The `owner` preset itself is just sugar for `record.<ownerField> = auth.id`, and `auth` is sugar for `auth.id != null` — writing them out is equivalent to picking the preset.

::: tip List rules are translated into a MongoDB filter
The same expression is used to build the query filter for `listRule` (comparisons, `in`, `and`/`or`/`not` all map onto MongoDB filters). `body.<field>` references don't apply here — there's no submitted body on a list request — so keep list/view rules to `record.*` and `auth.*` fields.
:::

## Worked example

A `posts` collection where anyone can read published posts, but only the author can edit or delete their own:

- **List / View**: `*` (public) — or `auth` if reads should require sign-in too.
- **Create**: `auth` — any signed-in tenant user can create a post (they become the owner automatically).
- **Update / Delete**: `owner` — only the post's author can modify or remove it.

## Where this leaves the two roles

To summarize the mental model:

- **Role** (`superadmin` vs. `user`) answers *"does this identity manage Paprika, or does it belong to one tenant's application?"*
- **Collection rules** answer *"which tenant users, if any, can perform this specific operation on this specific collection?"*

A superadmin is never subject to collection rules. A tenant user is always subject to them, on every request.
