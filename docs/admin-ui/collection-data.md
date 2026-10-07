# Collection: Data

`/admin/collections/:collection/data` — browse and edit the records stored in a collection, as a superadmin (bypassing that collection's own [Rules](/admin-ui/collection-rules) — see [admin bypass](/concepts/roles-and-permissions#admin-bypass-on-tenant-data)). Requires an [active tenant](/concepts/tenants).

## Browsing records

Records are shown in a table with one column per schema field plus `id`, `createdAt`, and `updatedAt`. Pagination controls (10/25/50/100 per page) sit below the table; the page size you pick is remembered in the browser and also applies to the [users](/admin-ui/tenant-users) table.

- **Search** runs on the server across the **whole collection**, not just the page on screen: a record matches when its `id` or any text field (`STRING`, `EMAIL`, `URL`, `SELECT`, `RELATION`) contains what you type, ignoring case. It applies after a short pause in typing, starts again on the first page, and the record count shows the matches. It uses the list API's [`search` parameter](/admin-ui/collection-api#listing-paging-filtering-and-sorting), so it never shows more than the collection's rules allow.
- **Sort** by any field with the sort dropdown and the direction toggle; it also runs on the server.
- **Timestamps** — `createdAt`, `updatedAt` and `DATETIME` fields — show the UTC date above the UTC time, so a column reads the same for everyone. Hover one for your local time.
- The **copy** button next to each `id` puts it on the clipboard, for API calls or relation fields.

## Creating and editing records

**New record** opens an empty record editor built from the collection's schema — one input per field, using the appropriate control for its type (text, number, toggle, date/time picker, select, relation picker, file upload). The **Edit** button in the row's Actions column opens the same editor pre-filled for editing.

- Fields with a configured **default value** are pre-filled on the new-record form.
- **FILE** fields upload via `multipart/form-data` behind the scenes automatically when the form is submitted — no separate action needed.
- **DATE**, **TIME** and **DATETIME** fields use the browser's native pickers, so the value always has the format the API expects: `yyyy-MM-dd` for `DATE`, `HH:mm:ss` for `TIME`.
- On the **new-record** form, **DATE** fields start at today's date and **DATETIME** fields at the current local date and time, because that is the value you want in most cases. A configured default value still wins. The **×** button next to every date/time picker clears it again — a cleared field is treated as "not set" (see below), and not every browser offers a way to empty these inputs on its own.
- A **DATETIME** is picked in local time and sent **with the offset of the picked moment** — `2026-09-22T10:00:00` entered in Central European Summer Time is sent as `2026-09-22T10:00:00+02:00` and stored in UTC as `2026-09-22T08:00:00.000Z`. The offset belongs to the chosen date, so a January and a July appointment get `+01:00` and `+02:00` respectively. Reading back works the other way round: a stored `2026-09-22T08:00:00Z` shows as `10:00` in a browser running at `+02:00`.
- Opening a record and saving it without touching a timestamp leaves that timestamp **byte for byte** as it was, including fractions of a second and an offset from another zone. Only a field you actually change is rewritten.
- **Clearing a field** means "not set": on create the field is left out of the payload, on update it is sent as `null`. An empty string is never sent — the API would reject it. Optional **SELECT** fields have a **No value** entry for exactly this, the same way `BOOLEAN` fields do; required fields don't offer it.
- A **STRING** field configured as [**Text**](/admin-ui/collection-schema#string-and-text) gets a multi-line textarea; line breaks are stored as typed.
- Values the API can't represent through the form (an exotic offset, an old value the picker can't show) stay editable in the **JSON** view, which sends exactly what you type.
- `id`, `createdAt`, and `updatedAt` are never editable; they're system-managed (see [Collections](/concepts/collections#system-fields)).

## Deleting records

Delete a single record with the **Delete** button in its row's Actions column or from inside its editor, or select multiple rows with the checkboxes and use **Delete selected** for a bulk delete. Each asks for confirmation first, and all of them are permanent — there's no trash/undo.

A bulk delete sends one request per record. If some of them fail — a [hook](/admin-ui/collection-hooks) blocks one, say — the others still go through: the message says how many were deleted (for example "2 of 3 records deleted") with the first reason, and the records that failed stay selected so you can retry them.

## Note on rules while editing here

Because the admin UI operates with an admin bypass, records that a normal tenant user couldn't see or edit (e.g. someone else's records under an `owner` rule) are still fully visible and editable here. Use this page to fix data or debug issues, not to verify what a specific tenant user can do — test that against the actual API instead.
