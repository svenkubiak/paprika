# Auth settings

**Auth settings** is the per-tenant configuration page for how tenant users register, reset their passwords, and verify their email addresses. Find it in the sidebar under a tenant's section — it requires an [active tenant](/concepts/tenants#the-tenant-switcher).

All three features are **off by default**. Enable only what your app needs. Changes take effect immediately without a restart.

## Self-registration

Controls whether `POST /api/auth/register` accepts new sign-ups for this tenant. When enabled, anyone with the tenant slug can create an account without superadmin involvement. When disabled, new users can only be created by a superadmin through the [Users](/admin-ui/tenant-users) tab.

Self-registration bypasses the `users` collection's create rule — that rule gates the data-plane endpoint (`POST /api/collections/users`), not the auth endpoint. See [Tenant Users — Rules tab](/admin-ui/tenant-users#rules-tab) for details.

## Password reset

Enables `POST /api/auth/password/forgot` and `POST /api/auth/password/reset` for this tenant. Paprika sends the reset email itself over the instance [SMTP settings](/operations/going-to-production#email-smtp).

**Reset link URL** — the page in your app that accepts the token. Paprika builds the email link from this URL: if the URL contains a `{token}` placeholder it is substituted directly (`https://app.example.com/reset/{token}`), otherwise the token is appended as a query parameter (`https://app.example.com/reset?token=…`). The URL must be set for emails to go out; without it the token is issued but no email is sent.

The full flow:

1. Your app calls `POST /api/auth/password/forgot` with the user's `email` and `tenant` slug.
2. Paprika issues a token (30-minute TTL, single-use) and emails the link to the user.
3. The user follows the link into your app, which calls `POST /api/auth/password/reset` with the `token` and new `password`.
4. Paprika validates the token, sets the new password (16-character minimum), and invalidates the token.

The request endpoint always answers `200` regardless of whether the account exists, to prevent account enumeration. The confirm endpoint returns a single generic error for any invalid, expired, or already-used token.

## Email verification

Enables `POST /api/auth/verify/request` and `POST /api/auth/verify/confirm` for this tenant. Confirming a token sets `emailVerified = true` on the user record.

**Verification link URL** — same mechanics as the reset URL above: set it to a page in your app that reads the token and calls the confirm endpoint. Without it, no email is sent.

The full flow:

1. Your app calls `POST /api/auth/verify/request` with the user's `email` and `tenant` slug.
2. Paprika issues a token (30-minute TTL, single-use) and emails the verification link to the user.
3. The user follows the link into your app, which calls `POST /api/auth/verify/confirm` with the `token`.
4. Paprika validates the token, sets `emailVerified = true`, and invalidates the token.

**Require for login** — when enabled, any login attempt where the user's `emailVerified` is still `false` is rejected before a token is issued. Use this to enforce verification as a precondition for access. This option is only available when email verification is enabled and has no effect if verification is off.

`emailVerified` is readable through the data plane (your app and [rules](/admin-ui/collection-rules) can inspect it) but is never client-writable and is not part of the editable schema.

### Mobile and native apps

The verification link URL works with deep links. Set it to a Universal Link (iOS) or App Link (Android) that your app is registered to handle, or a custom URL scheme (`myapp://verify?token={token}`). The app extracts the token and calls `POST /api/auth/verify/confirm` — the API call itself is a plain HTTP request that works identically on any platform.
