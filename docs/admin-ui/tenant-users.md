# Tenant Users

`/admin/users` redirects to `/admin/collections/users/data`. The tenant's user accounts now live in a real collection called `users`, and you manage them through the same tabs as any other collection: Data, Schema, Rules, Hooks, and API. It stays **superadmin only** and still needs an [active tenant](/concepts/tenants#the-tenant-switcher); the sidebar keeps a dedicated **Users** entry so you don't have to hunt for it in the collection list.

If no tenant is active, the page asks you to pick one before it shows anything.

These accounts are the ones that authenticate against `/api/auth/login` for the selected tenant. They have nothing to do with the superadmin account you sign in with, see [Roles & Permissions](/concepts/roles-and-permissions).

## Why it's a collection now

Previously `users` was a locked, hidden system table with a hard-coded shape. That made it awkward: you couldn't add your own fields, couldn't attach hooks to sign-ups or logins, and couldn't expose profile reads to your app without a workaround.

Now `users` is a normal collection with a few guardrails baked in. You can add custom fields, write Rules against it, and hook into both the usual CRUD lifecycle and the auth flows. What you can't do is break the parts Paprika relies on to keep authentication working, and those parts are locked rather than hidden so you can at least see them.

## Data tab

The Data tab keeps its user-focused layout instead of the generic record grid.

- **Self-registration** is configured under [Auth settings](/admin-ui/auth-settings), not on this tab.
- **User table.** Each row shows id, username, email, and role. **New user** opens an editor for username, password, and email. A password is required when creating and optional when editing, so leaving it blank keeps the current one. Passwords must be at least **16 characters** (the same floor as the superadmin password) and are hashed with Argon2 before storage. Password hashes are never shown and never returned by the API. A password shorter than the minimum is only rejected once you submit, with the error coming straight from the API.

Deleting a user is immediate and permanent, there's no soft-delete.

## Schema tab

The `users` collection ships with four core fields, and they're locked in the schema editor (marked with a lock icon, no edit and no delete):

- `username`, required, unique.
- `email`.
- `role`.
- `password`, a virtual, write-only field. You write a plaintext value on create or update and Paprika turns it into an Argon2 hash; it never reads back. The 16-character minimum applies here too.

The `passwordHash` and `passwordSalt` values Paprika stores internally are not part of the schema at all, so there's nothing to edit or leak there.

Everything else works like a normal [Schema tab](/admin-ui/collection-schema): add your own fields (a display name, an avatar file, a signup source, whatever your app needs) and they behave exactly like fields on any other collection. The core four survive edits, so re-injecting or reshaping the collection through the meta API won't drop them, and the unique username index is kept for you. Because `users` is a system collection, **Delete collection** is hidden.

## Rules tab

The five collection rules gate `/api/collections/users`, the data plane for user records, and work just like they do everywhere else (see [Rules](/admin-ui/collection-rules)). Common setups are an owner-scoped view/update so a signed-in user can read and edit their own profile, with list and create left locked.

Two things are specific to `users`:

- **Self-registration is not a rule.** The self-registration toggle under [Auth settings](/admin-ui/auth-settings) drives `/api/auth/register` and bypasses the collection rules entirely, so registration keeps working even when `createRule` is locked. The default locked create rule is the safe choice and does not get in the way of sign-ups. There's a warning on this tab spelling that out so nobody loosens the create rule expecting it to control registration.
- **`role` is read-only over the data plane.** Even with an owner update rule, a user can't promote themselves. The API ignores any `role` in the request body and pins it to `user`. The only way to change a role is the superadmin path.

## Hooks tab

On top of the usual CRUD events, the `users` collection exposes six auth events you won't find on any other collection:

| Event | Fires on | Blocking? |
|---|---|---|
| `beforeRegister` | `POST /api/auth/register` | Yes |
| `afterRegister` | `POST /api/auth/register` | No |
| `beforeLogin` | `POST /api/auth/login` | Yes |
| `afterLogin` | `POST /api/auth/login` | No |
| `beforeRefresh` | `POST /api/auth/refresh` | Yes |
| `afterRefresh` | `POST /api/auth/refresh` | No |

Use them to reject an unwanted sign-up, enrich the body before an account is created, or fire a side effect after someone logs in. The plaintext password is never handed to a hook: login and refresh envelopes don't carry it at all, and register redacts it before delivery. See [Collection Hooks](/admin-ui/collection-hooks#auth-events-users-only) for the envelope shape and the blocking contract.

Note that self-registration fires `beforeRegister` and `afterRegister`, while creating a user from the Data tab (or through `POST /api/collections/users`) fires `beforeCreate` and `afterCreate`. The two paths don't overlap, so a hook won't get delivered twice for one account.

## API tab

The auto-generated [API reference](/admin-ui/collection-api) covers `users` like any collection, with one difference: realtime is off for it. You can't subscribe to the `users` collection over SSE and Paprika never broadcasts user changes, so the realtime card is hidden here. Watching account records live isn't something an app should be doing, and turning it off keeps password-adjacent data out of any live stream.

## Password reset and email verification

Two optional, per-tenant recovery flows for tenant users. Both are **off by default** and configured independently per tenant under [Auth settings](/admin-ui/auth-settings). Paprika **sends the email itself** over the instance's [SMTP settings](/operations/going-to-production#email-smtp), but the link points at **your** app: Paprika owns the token, your app renders the screens. Paprika sends no page to end users.

### How it works

The shape is the same for both flows:

1. Your app calls the request endpoint (`POST /api/auth/password/forgot` or `POST /api/auth/verify/request`) with the user's email and the tenant slug.
2. If the tenant has the feature enabled and the email matches a user, Paprika issues a short-lived, single-use token and **emails the link** to that user over the instance SMTP configuration. The link is built from the tenant's configured **reset URL** (or **verification URL**): Paprika substitutes a `{token}` placeholder if the URL has one, otherwise it appends `?token=…`.
3. The user opens the link in **your** app, which collects the new password (for reset) and calls the confirm endpoint (`POST /api/auth/password/reset` or `POST /api/auth/verify/confirm`) with the token.
4. Paprika validates the token, applies the change, and invalidates the token.

Tokens live for **30 minutes** and work once. Two things have to be in place for the email to actually go out: the instance needs working SMTP settings, and the tenant needs its reset (or verification) link URL configured. If the URL is missing, the token is still issued but no email is sent, so configure the URL when you turn the feature on.

### What each flow does

- **Password reset** sets a new password (the usual 16-character minimum) and invalidates the token. Old passwords stop working immediately.
- **Email verification** sets the user's `emailVerified` flag to `true`. By itself it does not block login — it's a flag your app or your [rules](/admin-ui/collection-rules) can check (for example `record.emailVerified = true`). If you want to enforce verification before users can sign in, enable **Require for login** under [Auth settings](/admin-ui/auth-settings): Paprika will then reject any login where `emailVerified` is still `false`.

### No account enumeration

The request endpoints always answer `200`, whether or not the tenant, the feature, or the account exists. A caller can't use them to discover which emails have accounts or which tenants have the feature on. The confirm endpoints answer with a single generic error for any bad, expired, already-used, or disabled-feature token, for the same reason.

### The `emailVerified` field

`emailVerified` is system-managed. It's readable through the data plane (so your app and rules can see it) but never client-writable, and it isn't part of the editable schema. The reset and verification tokens themselves are stored hashed and are never returned by any API.

## What a tenant user can actually do

Creating a user still only creates an *identity*. What that user can read or write through the API is decided entirely by each collection's [Rules](/admin-ui/collection-rules), including the rules on `users` itself now. There's no per-user permission override on this page.
