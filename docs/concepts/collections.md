# Collections

A **collection** is Paprika's equivalent of a database table: a named, schema-defined set of records, scoped to the active tenant, that automatically gets a REST API. Collections are created empty (see [New collection](/admin-ui/collection-schema#creating-a-collection)) and then shaped with fields on the Schema tab.

## Fields

Each field has a **name** (letters, numbers, underscores — used as the JSON key in API requests/responses) and a **type**:

| Type | Stores | Notable options |
|---|---|---|
| `STRING` | Text | `minLength`, `maxLength`, regex `pattern` |
| `NUMBER` | Number (incl. decimals) | `numberMin`, `numberMax` |
| `BOOLEAN` | true/false | default value |
| `EMAIL` | Text, validated as an email address | — |
| `URL` | Text, validated as a URL | — |
| `DATE` | ISO date (`yyyy-MM-dd`) | `minDate`, `maxDate` |
| `TIME` | Time of day | `minTime`, `maxTime` |
| `DATETIME` | ISO timestamp with timezone | `minDateTime`, `maxDateTime` |
| `SELECT` | One or more values from an allow list | `values`, `maxSelect` |
| `JSON` | Arbitrary JSON | `maxBytes`, `maxDepth`, `onlyObject`/`onlyArray` |
| `RELATION` | ID(s) of record(s) in another collection | target `collection`, `maxSelect`, `cascadeDelete` |
| `FILE` | Uploaded file(s), stored alongside the record | `maxSize`, `mimeTypes`, `maxSelect` |

Any field can be marked **required** (must be present and non-empty on create) and given a **default value** applied when the field is omitted on create.

## Relations

A `RELATION` field stores the id (or ids, if `maxSelect > 1`) of records in another collection. Relations to the built-in `users` collection are how ownership works — see [Roles & Permissions](/concepts/roles-and-permissions#the-rule-engine-how-tenant-user-data-access-actually-works). Enabling **cascade delete** on a relation means deleting the referenced record also deletes the records that point to it.

Relations are validated when the record that holds them is written: create/update fails with a validation error if a referenced id doesn't exist in the target collection at that moment. There's no ongoing enforcement after that, though — if the target record is later deleted **without** cascade delete enabled, the relation field is left pointing at an id that no longer exists (a dangling reference), and nothing revalidates or cleans it up automatically.

## Files

`FILE` fields are part of the schema, not a bolted-on system: they're validated (max size, allowed MIME types, max count) the same way any other field is, and they follow the same [collection rules](/concepts/roles-and-permissions) as the rest of the record — a `viewRule` that denies a user also denies downloading that record's files. Files are cleaned up automatically when their record is deleted or when a file field is overwritten. FILE fields can't be sent as JSON — creating or updating a record with a file requires a `multipart/form-data` request (see the [API Reference tab](/admin-ui/collection-api) for exact examples per collection).

On disk, uploaded files are stored under `<PAPRIKA_STORAGE>/<tenantId>/<fileId>` — one folder per tenant, flat within it. [Deleting a tenant](/admin-ui/tenants#deleting-a-tenant) removes this folder along with the tenant's MongoDB database, so no files are left behind.

## Indexes

Any field can have a single-field MongoDB index, with a chosen sort direction and an optional uniqueness constraint, to speed up filtering/sorting or to enforce no-duplicates at the database level. Compound (multi-field) indexes exist in the data model and are preserved if a collection already has one, but there's no UI to create one — the Schema tab's per-field index toggle only ever produces single-field indexes.

## System fields

Every record automatically gets three fields that are **not** part of the schema and can't be set by API clients:

- `id` — the record's unique identifier.
- `createdAt` — set once, when the record is created.
- `updatedAt` — refreshed every time the record is saved.

`id`, `createdAt`, `updatedAt` (and the legacy names `created`/`updated`) are reserved and can't be used as schema field names.

## Where collections show up

Each collection has five tabs in the admin UI, one per concern:

- [Data](/admin-ui/collection-data) — browse and edit records.
- [Schema](/admin-ui/collection-schema) — define fields and indexes (this page).
- [Rules](/admin-ui/collection-rules) — who can list/view/create/update/delete records via the API.
- [Hooks](/admin-ui/collection-hooks) — webhooks tied to this collection's lifecycle events.
- [API Reference](/admin-ui/collection-api) — auto-generated REST/SSE documentation for this collection.
