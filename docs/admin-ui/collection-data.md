# Collection: Data

`/admin/collections/:collection/data` — browse and edit the records stored in a collection, as a superadmin (bypassing that collection's own [Rules](/admin-ui/collection-rules) — see [admin bypass](/concepts/roles-and-permissions#admin-bypass-on-tenant-data)). Requires an [active tenant](/concepts/tenants).

## Browsing records

Records are shown in a table with one column per schema field plus `id`, `createdAt`, and `updatedAt`. Use the search box to filter across all visible fields, and the sort dropdown/direction toggle to order by any field. Pagination controls (10/25/50/100 per page) sit below the table.

::: warning Search and sort only apply to the currently loaded page
Paprika's list API only supports `offset`/`limit` — it has no server-side search or sort. So on this page, search and sort run entirely in the browser, against whatever page of records is currently loaded (25 by default), **not the whole collection**. Searching for a value that exists on page 3 while viewing page 1 won't find it; you'd need to increase the page size or page through manually. This is different from [Request Logs](/admin-ui/request-logs), where search and filtering genuinely happen server-side.
:::

## Creating and editing records

**New record** opens an empty record editor built from the collection's schema — one input per field, using the appropriate control for its type (text, number, toggle, date/time picker, select, relation picker, file upload). Clicking a row (or its id) opens the same editor pre-filled for editing.

- Fields with a configured **default value** are pre-filled on the new-record form.
- **FILE** fields upload via `multipart/form-data` behind the scenes automatically when the form is submitted — no separate action needed.
- `id`, `createdAt`, and `updatedAt` are never editable; they're system-managed (see [Collections](/concepts/collections#system-fields)).

## Deleting records

Delete a single record from its editor, or select multiple rows with the checkboxes and use **Delete selected** for a bulk delete. Both are permanent — there's no trash/undo.

## Note on rules while editing here

Because the admin UI operates with an admin bypass, records that a normal tenant user couldn't see or edit (e.g. someone else's records under an `owner` rule) are still fully visible and editable here. Use this page to fix data or debug issues, not to verify what a specific tenant user can do — test that against the actual API instead.
