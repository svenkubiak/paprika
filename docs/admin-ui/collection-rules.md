# Collection: Rules

`/admin/collections/:collection/rules` — configure who can perform each API operation on this collection. This page is the control surface for the concept explained in [Roles & Permissions → The rule engine](/concepts/roles-and-permissions#the-rule-engine-how-tenant-user-data-access-actually-works); read that first if the terms below (public/auth/owner/locked) are unfamiliar.

## Operation rules

Five independent rules, each shown with the exact HTTP endpoint it governs:

| Rule | Endpoint |
|---|---|
| List | `GET /api/collections/{collection}` |
| View | `GET /api/collections/{collection}/{id}` |
| Create | `POST /api/collections/{collection}` |
| Update | `PATCH /api/collections/{collection}/{id}` |
| Delete | `DELETE /api/collections/{collection}/{id}` |

Each has a dropdown with four levels:

- **No access** — locked, nobody can call it.
- **Public** — anyone, no authentication.
- **Signed in** — any authenticated tenant user.
- **Own records** — only the tenant user referenced by the owner field.

## Presets

The **Presets** card applies all five rules at once for a common pattern (e.g. "Public" makes everything public; "Own records" makes everything owner-scoped). Apply a preset first, then fine-tune individual rules if one operation needs a different level than the rest.

## Beyond the four presets: custom expressions

The dropdowns only offer **No access / Public / Signed in / Own records**, but the underlying rule value is actually a small expression language — comparisons, `and`/`or`/`not`, `in (...)`, and access to `record.*`, `auth.*`, and (for create rules) `body.*` fields. See [Roles & Permissions → Custom expressions](/concepts/roles-and-permissions#custom-expressions) for the full grammar and examples like `record.status = "published"` or `record.age >= 18`.

To use a custom expression, set it via the collection's meta API (`PATCH /api/meta/collections/{collection}/{id}`, superadmin-authenticated) directly on the `rules` object's `listRule`/`viewRule`/`createRule`/`updateRule`/`deleteRule` fields — it's then honored by the API exactly like a preset.

::: danger Don't open and save this tab for a collection with a custom rule
The Rules tab only recognizes the four presets. It detects them with a simple heuristic (exactly `*`, exactly/starting with `auth`, or a value that happens to contain both `record.` and `auth.id`) — anything else, including most genuinely custom expressions, is shown as **No access**. If you then click **Save rules** on that screen, it overwrites the custom expression with `null` (locked), silently destroying it.

If a collection has a custom rule expression set via the API, manage that rule through the API going forward and avoid saving changes from this tab — check the current value with `GET /api/meta/collections/{collection}` first if you're unsure what's configured.
:::

## Owner field

When any rule is set to **Own records**, an **Owner field** selector appears. It must be a `RELATION → users` field on this collection (see [Collections → Relations](/concepts/collections#relations)); Paprika sets it automatically to the creating user's id when a record is made. If the collection has no such relation field yet, add one on the [Schema tab](/admin-ui/collection-schema) first — the dropdown here falls back to a placeholder `owner` value until you do.

## The users collection

Rules on the [`users` collection](/admin-ui/tenant-users) gate `/api/collections/users` exactly like any other collection, but two things behave differently and there's a warning on the tab to match:

- **Self-registration ignores these rules.** The registration toggle on the Users Data tab drives `/api/auth/register` and doesn't touch `createRule`. Leaving create locked is the safe default and won't stop people from signing up. Don't loosen the create rule expecting it to control registration; it controls `POST /api/collections/users` instead.
- **`role` can't be set through the data plane.** Even with an owner update rule, the API pins `role` to `user` and ignores any value in the body, so a user can't promote themselves. Roles only change via the superadmin path.

## Things worth knowing

- **List rules also filter results**, not just gate the endpoint: an "Own records" list rule only ever returns the calling user's own records, never the full collection.
- These rules apply to regular tenant-user API calls only. A superadmin browsing this collection from the [Data tab](/admin-ui/collection-data) bypasses them entirely (see [admin bypass](/concepts/roles-and-permissions#admin-bypass-on-tenant-data)) — don't use the admin UI to "test" what a rule allows; call the API as a tenant user instead.
- API clients authenticate with `Authorization: Bearer <accessToken>`, obtained from `POST /api/auth/login` (see [API Reference](/admin-ui/collection-api)).
