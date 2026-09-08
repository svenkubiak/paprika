# Backup & Restore

Part of the [Settings](/admin-ui/settings) page — instance-wide backup and restore, superadmin only.

## Export

**Download backup** downloads a single ZIP archive containing **all tenants**: their collections, schemas, rules, hooks, records, and uploaded files. It's an all-tenant snapshot, not a per-tenant export — there's no way to back up or restore a single tenant in isolation from this page.

## Restore

Uploading a previously exported ZIP restores its contents.

::: danger This overwrites everything
Restoring a backup **permanently overwrites all existing tenants, collections, data, and files** with whatever is in the ZIP. It is not a merge — anything created since the backup was taken, in any tenant, is lost. The confirmation dialog spells this out; read it before confirming, especially on a production instance.
:::

## When to use this

Because export/restore covers the whole instance, treat it as instance-level disaster recovery (moving to new infrastructure, restoring after data loss) rather than a per-tenant undo button. For everyday mistakes, fixing individual records on a collection's [Data tab](/admin-ui/collection-data) is safer than restoring a whole backup.
