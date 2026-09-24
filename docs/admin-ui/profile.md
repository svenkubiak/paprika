# Profile

`/admin/profile` is your own superadmin account: password, two-factor authentication, email address, profile picture and sign-in alerts. Reach it through the avatar and name in the top right corner of every page. Everything here applies to **the account you're signed in with** — you can never change another superadmin's profile from here. To add or remove accounts, see [Superadmins](/admin-ui/superadmins).

## Account

- **Username** — shown read-only. It's the name you sign in with and is fixed for the lifetime of the account.
- **Profile picture** — PNG, JPEG or WebP. Your browser centre-crops and scales the file to 256×256 pixels before uploading, so only image data reaches the server, never the original file. The picture is stored on the account itself (not in the file storage), which means it travels with the [instance backup](/admin-ui/backup-restore). It's shown next to your name in the header.

## Email address

Paprika only uses this address for notifications about your own account — today that's the sign-in alert below. Superadmin invites are a separate thing and are addressed on the [Superadmins](/admin-ui/superadmins) page.

- **Confirmation is required.** Saving an address stores it as *unconfirmed* and sends a confirmation link to it. Until you open that link, Paprika sends nothing else there, so a typo can't quietly redirect your account mail to a stranger. The link expires after 30 minutes, can be used once, and carries its token in the URL fragment — it never reaches the server as part of a URL, so no access log or proxy ends up holding it.
- **Send confirmation link** re-sends it for an address that's still unconfirmed.
- **Remove address** deletes it and switches the sign-in alert off with it.
- Changing an already confirmed address makes it unconfirmed again and starts the same flow.
- All of this needs SMTP. If the instance still has the default SMTP settings, the page says so: the address can be stored, but no confirmation link can be sent and nothing here becomes usable. See [Configuration](/installation/configuration) for the `smtp.*` keys.

## Sign-in alerts

Off by default. When it's on, Paprika emails you when your superadmin account is signed in **from a device it hasn't been used from before**.

- **How a device is recognised**: by a hash of the browser's user agent and the client IP address of the request. Nothing else is stored — not the user agent, not the address, only that hash, and only for the last 20 devices. This is independent of the [request-log client-info settings](/admin-ui/settings#request-logs): those decide what Paprika *keeps* about a caller, while nothing is kept here.
- **What counts as new**: a different browser, a different machine, or a different network. A dynamic IP therefore produces an occasional extra mail — that's the trade-off for not asking a third-party geo service on every login.
- **Quiet for devices you already use.** An alert on every single login would be ignored within a day and would protect nothing.
- **Switching it on trusts the device you're switching from**, so enabling the alert doesn't immediately mail you about the session you're already in.
- The alert covers both ways in: the admin UI session and the API token flow (`/api/admin/token`). A pending 2FA challenge is not a sign-in — only the confirmation step counts.
- The mail names the time, the IP address and the browser, and carries nothing that could be used to sign in.
- The switch can only be turned on with a **confirmed** email address and configured SMTP. Without either, the page says which one is missing.

## Security

- **Password** — change the password of the account you're signed in with. Requires the current password and a new password of at least 16 characters.
- **Two-factor authentication (TOTP)** — enable or disable a second sign-in factor **for your own account**. Standard TOTP (6-digit codes, 30-second period), so any authenticator app works:
  - **Enable**: confirm your current password, scan the QR code (or enter the manual key) in an authenticator app, then confirm with a 6-digit code. You're shown a one-time **fallback code** afterwards — store it somewhere safe, it's the way back in if you lose the authenticator and it's shown only once.
  - **Disable**: requires both your password and a current 6-digit code, a deliberate extra check since disabling 2FA reduces account security.
  - 2FA is **per superadmin account**. There's no instance-wide switch that forces or removes it for everyone; each superadmin decides for their own login, and you can see who has it enabled on the [Superadmins](/admin-ui/superadmins) page. There's no equivalent setting for tenant users.
