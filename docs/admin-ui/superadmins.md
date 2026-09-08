# Superadmins

`/admin/superadmins` (**superadmin only**). Manages the superadmin accounts that operate the Paprika instance. This is where you add more superadmins, see who currently has access, and remove people who shouldn't.

A Paprika instance can have more than one superadmin. The first one is created during [initial setup](/installation/initial-setup); every additional one is added here by invite. All superadmins are equal, there is no separate "read only" or scoped admin role, so only hand this out to people you'd trust with the whole instance.

## The list

Each row is one superadmin:

- **Username** and **email**. Your own account is tagged with a **You** badge.
- **Status**: *Active* for a completed account, or *Pending invite* for someone who has been invited but hasn't finished setup yet.
- **2FA**: whether that account has two-factor authentication enrolled. This is per account (see [Settings → Security](/admin-ui/settings#security)), so it's normal for some superadmins to have it on and others not. A pending invite shows nothing here until the account exists.

## Adding a superadmin by invite

There's no way to set someone else's password for them, so new superadmins are added with a one-time setup link:

1. Click **New superadmin**, enter a username and optionally an email.
2. Paprika creates a **pending invite** and shows a one-time setup link, with two ways to deliver it.
3. Get the link to the person:
   - **Copy** the link and send it yourself through a channel you trust (a password manager, a direct message). Always available.
   - Or, if the instance has [SMTP configured](/operations/going-to-production#email-smtp), type an address and click **Send by email** to have Paprika email the link. This option only appears when SMTP is set up; without it, use Copy.
4. They open it, choose their own password (at least 16 characters), and are signed in as a superadmin. The row flips from *Pending invite* to *Active*.

The link is valid for **30 minutes** and works once. If it expires before they use it, revoke the pending invite and create a new one. The invitee sets their own password, so it's never something you see or type on their behalf. The emailed link uses the address you reached the admin UI on, so it works as long as your instance is served under a stable public URL.

## Removing a superadmin

**Remove** deletes a completed account; **Revoke** cancels a pending invite (its setup link stops working immediately). Both are on the same action button, labelled to match the row.

Two guardrails:

- **The last remaining superadmin can't be removed.** There's always at least one account that can operate the instance, so Paprika refuses to delete the final active superadmin.
- **You can't remove your own account** from this page. Removing yourself mid-session would be an easy way to lock yourself out, so if you need to step down, have another superadmin remove you.

Removing a superadmin takes effect right away: their session stops working on the next request.

## A note on the initial setup link

The very first superadmin is still bootstrapped through the one-time link printed to the server log on a fresh install. That path only ever applies while no superadmin exists yet. Once one does, further accounts come exclusively through the invite flow above. Paprika only prints a fresh setup link again when there is no completed superadmin left at all, and the last-admin guard means you can't reach that state from the UI. So a total lockout (every superadmin's credentials lost) is recovered at the database level, not through the admin UI. The practical takeaway: make sure at least one superadmin account always stays recoverable, ideally more than one, which is exactly what this page is for.
