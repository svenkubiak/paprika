# Backup & Restore

Part of the [Settings](/admin-ui/settings) page — instance-wide backup and restore, superadmin only.

## Export

**Download backup** downloads a single ZIP archive containing **all tenants**: their collections, schemas, rules, hooks, records, and uploaded files. It's an all-tenant snapshot, not a per-tenant export — there's no way to back up or restore a single tenant in isolation from this page.

## Restore

Uploading a previously exported ZIP restores its contents.

::: danger This overwrites everything
Restoring a backup **overwrites all existing tenants, collections, data, and files** with whatever is in the ZIP. It is not a merge — anything created since the backup was taken, in any tenant, is lost. The confirmation dialog spells this out; read it before confirming, especially on a production instance.
:::

### What happens before anything is written

The archive is read and checked in full first. If an entry is missing or unreadable — no `system/users.json`, a tenant the manifest lists but does not describe, a file that is not valid JSON — the restore is refused and **nothing is written at all**. An incomplete archive is treated as a broken archive, never as a set of empty collections.

An archive that contains no superadmin with a password is refused for the same reason: applying it would leave the instance with no way to sign in.

Once the archive has passed, Paprika exports the **current** state and writes it to `pre-import-snapshots/` inside the [storage directory](/installation/configuration) (`PAPRIKA_STORAGE`) before the first write. The path is shown when the restore finishes and is included in the error message if the restore fails halfway through — restoring that archive puts the instance back. These snapshots are never deleted automatically; prune them yourself as part of your housekeeping.

::: warning Only restore archives you produced yourself
A backup contains the **superadmin accounts**, password hashes included. Restoring an archive somebody else handed you replaces your superadmins with theirs, which gives whoever created it access to this instance. Treat a backup file as a credential, not as a data file: keep it where you keep your secrets, and never restore one from an untrusted source.
:::

## When to use this

Because export/restore covers the whole instance, treat it as instance-level disaster recovery (moving to new infrastructure, restoring after data loss) rather than a per-tenant undo button. For everyday mistakes, fixing individual records on a collection's [Data tab](/admin-ui/collection-data) is safer than restoring a whole backup.
