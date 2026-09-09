# Initial Superadmin Setup

Paprika ships with no default credentials. On first start against a fresh database — no completed superadmin exists yet — it generates a one-time setup token and prints a link to the application log:

```
Complete initial superadmin setup within 30 minutes: /setup#token=<token>
```

Startup logs are noisy, so filter for the token instead of scrolling: for the Docker install, `docker compose logs -f | grep setup`; for the standalone service, `journalctl -u paprika -f | grep --line-buffered setup`.

## Completing setup

Open that URL in your browser (it points at whatever host and port Paprika is reachable on), pick a username and a password of at least 16 characters, and you're signed in as a superadmin. The username can be anything you like, and you can change your password later under **Settings → Security**.

The token itself never leaves the URL fragment (`#token=...`), so it isn't sent to the server as part of the request and stays out of access logs. It's valid for **30 minutes** and works once.

## If the token expires

Restart Paprika. As long as no superadmin account has completed setup yet, it prints a fresh token on startup. Once a superadmin exists with a password, this path closes — the log will no longer print setup links, and further superadmin accounts are created exclusively through the invite flow described in [Superadmins](/admin-ui/superadmins).

## Add more than one superadmin

A single superadmin is a single point of failure — losing that one account's credentials with nothing else configured means recovering at the database level. Once you're in, go to [Superadmins](/admin-ui/superadmins) and invite at least one more. All superadmins are equal; there's no scoped or read-only role.

## Next steps

Head to **Settings → Security** to enable TOTP two-factor authentication for your account, then start defining collections. See [Concepts → Collections](/concepts/collections) for the data model, or jump straight into the [Admin UI Guide](/admin-ui/dashboard).
