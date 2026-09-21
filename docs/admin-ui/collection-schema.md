# Collection: Schema

`/admin/collections/:collection/schema` — define the fields, validation, and indexes for a collection. Background on field types and options is in [Concepts → Collections](/concepts/collections).

## Creating a collection

Use **New collection** (available from the sidebar's collection list). Names are normalized to lowercase letters, numbers, and underscores. A collection is created with an empty schema — you land straight on its Schema tab to start adding fields.

## Adding and editing fields

**Add field** opens the field editor: name, type, required flag, and type-specific options (length/range limits, regex pattern, allowed select values, relation target and cascade-delete, file size/MIME/count limits, JSON depth/size limits, date/time ranges, default value). Every option has an inline hint (hover the **?** next to its label) explaining exactly what it does and how to format it.

A few validation rules are enforced when saving a field:

- The field name can't be a [reserved system field name](/concepts/collections#system-fields) (`id`, `createdAt`, `updatedAt`, or the legacy `created`/`updated`), and can't duplicate another field on the same collection.
- A `RELATION` field must specify a target collection.
- A `FILE` or `SELECT` field must allow at least one selection (`maxSelect ≥ 1`), and a `SELECT` field needs at least one allowed value.

## Image widths on a `FILE` field

A `FILE` field can list **image widths** (comma separated, for example `320, 800, 1600`). Every image uploaded to that field is then stored together with one downscaled copy per width, and clients fetch them with [`?width=`](/admin-ui/collection-api#image-variants-width).

The limits, enforced both in this editor and by the API:

- **At most 4 widths per field.** Four cover list, preview, detail and retina; every further width multiplies the storage each upload costs.
- **At most 4096 px per width.** Beyond that a "variant" is no longer a smaller copy.
- Widths are normalized: duplicates are dropped and the list is sorted ascending.

What gets scaled:

- Only **JPEG, PNG and GIF** — the formats the JVM can both read and write without an extra library. WebP in particular can be read but not written, so a WebP upload keeps only its original.
- Only **downwards**: an image narrower than a configured width gets no variant for that width and falls back to a larger variant, or to the original, on download.
- The EXIF orientation of a photo is applied to the pixels, so a variant is never tilted against its original.
- If scaling fails, the upload still succeeds — the original is the payload, a missing variant only costs bandwidth.

Changing the widths affects **new uploads only**. Files that already exist are not rescaled; they keep answering through the fallback.

::: warning Field names are permanent
Once a field exists, its name can't be changed from this UI — only its other options. Renaming means deleting the field and adding a new one (see below).
:::

## Indexing a field

Each field row shows whether it's indexed, in which direction (ascending/descending), and whether it's unique. Enable indexing in the field editor to speed up filtering/sorting on that field, or to enforce that no two records share the same value.

::: tip Index what you sort by
The list endpoint's [`sort` parameter](/admin-ui/collection-api#listing-paging-filtering-and-sorting) works on any sortable field, indexed or not. Without an index MongoDB sorts in memory, and that sort fails once it exceeds 32 MB — so for a collection that grows, index the field your clients sort by.
:::

## Deleting a field

Deleting a field removes it from the schema, but **does not** delete the field's data from existing records in MongoDB — it simply stops being validated, shown, or served by the API. If you re-add a field with the same name later, old values may reappear.

## System collections have locked fields

The tenant [`users` collection](/admin-ui/tenant-users) is a real collection you can extend with your own fields, but its core fields (`username`, `email`, `role`, and the virtual write-only `password`) are locked. They show a lock icon and can't be edited or deleted, since Paprika needs them for authentication to work. Add whatever custom fields you like around them; those behave normally. For a system collection, **Delete collection** is also hidden.

## Deleting the whole collection

**Delete collection** removes the collection definition and drops its underlying MongoDB collection — all records in it are gone, not just their schema. This is separate from, and more destructive than, deleting individual records on the [Data tab](/admin-ui/collection-data). Built-in system collections (like the tenant's internal `users` collection) can't be deleted this way.
