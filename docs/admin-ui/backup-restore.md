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

## How big an instance this works for

Export, snapshot and restore all happen **in memory and in one piece**: the whole archive is
built as a single byte array, and a restore holds the uploaded ZIP, every unpacked entry and the
parsed documents at the same time. That puts a ceiling on it, and the ceiling is lower than most
people expect.

| Limit | Value | Where it comes from |
|---|---|---|
| Upload size of a restore | **4 MiB** | Undertow's `undertow.maxentitysize`; a larger request body is refused before Paprika sees it |
| Upload size through the proxy | **1 MB unless you change it** | nginx's `client_max_body_size` default — set it to `4m`, see [nginx example](/operations/going-to-production#nginx-example) |
| Uncompressed archive contents | 512 MB | Checked while unpacking, as a zip-bomb guard |
| Entries in the archive | 10 000 | Collections plus uploaded files, across all tenants |
| Download size of an export | no limit | Only your heap |

The first row is the one that decides. **A restore works as long as the ZIP is at most 4 MiB** —
compressed, so with JSON data that is a fair amount of records, but not many uploaded files.
Raising it means replacing the whole bundled `config.yaml` via `-Dapplication.config=…`, which is
a maintenance burden of its own; treat 4 MiB as the number to plan with.

::: danger An export is not proof of a working restore
Nothing limits the size of a **download**, so an instance that has grown past the upload limit
keeps producing backups happily — and none of them can be restored through this page. Check the
size of your export after every growth step, and while you are at it, restore it into a staging
instance once. A backup you have never restored is a hypothesis.
:::

**Memory.** Plan for several times the archive size in free heap during a restore: uploaded ZIP,
unpacked entries, parsed BSON documents and the pre-import snapshot of the current state are all
live at the same time. The packaged install runs with `-XX:MaxRAMPercentage=70` and
`-XX:+ExitOnOutOfMemoryError`, so an oversized restore ends the process instead of limping on —
and because the snapshot is written before the first write, the state from before is on disk
either way. In Docker the JVM defaults to 25 % of the container memory unless you set
`JAVA_OPTS`.

**Beyond that.** For an instance too large for this page, back up at the infrastructure level:
`mongodump` (or your provider's snapshots) for the databases, and a file-level copy of
`PAPRIKA_STORAGE` for the uploads, taken together. That path has no size limit and no memory
cost in the application — it just does not give you the one-click restore this page does.

**The snapshot directory.** `pre-import-snapshots/` grows by one full backup per restore, sits
inside `PAPRIKA_STORAGE` with whatever permissions that directory has, and contains superadmin
password hashes and every tenant's data. Protect it like a backup file and prune it deliberately.

## When to use this

Because export/restore covers the whole instance, treat it as instance-level disaster recovery (moving to new infrastructure, restoring after data loss) rather than a per-tenant undo button. For everyday mistakes, fixing individual records on a collection's [Data tab](/admin-ui/collection-data) is safer than restoring a whole backup.
