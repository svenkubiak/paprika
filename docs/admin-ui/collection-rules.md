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

Each has a dropdown with these levels:

- **No access** — locked, nobody can call it.
- **Public** — anyone, no authentication.
- **Signed in** — any authenticated tenant user.
- **Own records** — only the tenant user referenced by the owner field.
- **Group members** — only users who are members of the group the record belongs to. Offered on every collection except `users`.
- **Group peers** — only users who share a group with the caller, plus the caller's own record. Offered on `users` only.

## Presets

The **Presets** card applies all five rules at once for a common pattern (e.g. "Public" makes everything public; "Own records" makes everything owner-scoped). Apply a preset first, then fine-tune individual rules if one operation needs a different level than the rest.

## No custom expressions

Unlike PocketBase, Paprika deliberately does not expose a custom rule expression syntax. The presets — **No access / Public / Signed in / Own records / Group members / Group peers** — are the only values the API accepts for `listRule`/`viewRule`/`createRule`/`updateRule`/`deleteRule`; anything else sent via the collection's meta API (`PATCH /api/meta/collections/{collection}/{id}`) is rejected with an error. This keeps rule configuration simple and fully representable in the admin UI, at the cost of not supporting more specific per-field or conditional access patterns.

## Owner field

When any rule is set to **Own records**, an **Owner field** selector appears. It must be a `RELATION → users` field on this collection (see [Collections → Relations](/concepts/collections#relations)); Paprika sets it automatically to the creating user's id when a record is made. If the collection has no such relation field yet, add one on the [Schema tab](/admin-ui/collection-schema) first — the dropdown here falls back to a placeholder `owner` value until you do.

**Except on the `users` collection**, where no owner field is involved at all — see below. The selector is not shown there.

## Group configuration

As soon as one rule is set to **Group members** or **Group peers**, four more selectors appear.
They say where the memberships live; the concept behind them is
[Group membership](/concepts/roles-and-permissions#group-membership-group-and-peers).

| Selector | Stored as | Meaning |
|---|---|---|
| **Membership collection** | `groupCollection` | The collection with one record per membership, e.g. `team_members`. This collection itself can be picked — it is listed as *this collection — the memberships themselves* — which is how the members of a group get to see their group's membership records, i.e. the member list. |
| **Member field** | `groupMemberField` | The field in there pointing at the user — a `RELATION → users` or a `STRING` holding the id. |
| **Group field** | `groupField` | The field in there pointing at the group. |
| **Group field on this collection** | `groupRecordField` | The field of *this* collection carrying the group. Only shown for **Group members**; **Group peers** matches on the record id. |

### When this collection *is* the group

On the group collection itself — `teams` in the example — no field points at a group, because each
record is one. **Group field on this collection** therefore offers `id` on top of the declared
fields: picking it means *the record is the group*, and every member of a team reaches their team's
record without a second field duplicating its id.

The **Create rule** has to be a different preset there — **Signed in** or **Own records**. A
**Group members** create rule with `id` could never be satisfied (`id` cannot be sent by a client,
and nobody is a member of a group that does not exist yet), so saving that combination is refused
with `400`. Update and delete stay open to every member of the group; use **Own records** if only
the creator should change it.

### When this collection *holds the memberships*

On the membership collection itself — `team_members` in the example — **Membership collection** can
be set to this very collection. **Group field on this collection** is then its own group field
(`team`), and every member of a team sees all membership records of that team: the member list.
The lookup is resolved once, before the list is scoped, so this is not a circular reference. Keep
the write rules narrower (locked or **Own records**) unless every member may change memberships.

The field dropdowns are filled from the schema of the collection you pick, so pick the membership
collection first. Saving with an incomplete configuration, an unknown collection or an unknown
field is refused with `400` and a message naming what is wrong — the rules are never stored in a
state where they do not mean what they say.

::: warning Add the two indexes
Every API request against this collection queries the membership collection to resolve the
caller's groups. On its [Schema tab](/admin-ui/collection-schema), add an index on the **member
field** and one on the **group field** — without them, each request costs a full scan of that
collection.
:::

On `users`, **Group peers** treats Update like View: a user could then edit their teammates'
records, not just read them. If the application only needs to *show* teammates, set View (and
List) to **Group peers** and leave Update on **Own records**. Create is never granted by it at all: sign-up goes through `POST /api/auth/register`.

Two things that are easy to expect and do not happen: the group field is **not** filled in on
create (unlike the owner field — the client sends it, the rule checks it), and the membership
collection's own rules are **not** consulted for this lookup. Give that collection its own rules;
locked or **Group members** are the usual choices.

## The users collection

Rules on the [`users` collection](/admin-ui/tenant-users) gate `/api/collections/users` exactly like any other collection, but two things behave differently and there's a warning on the tab to match:

- **Self-registration ignores these rules.** The registration toggle on the Users Data tab drives `/api/auth/register` and doesn't touch `createRule`. Leaving create locked is the safe default and won't stop people from signing up. Don't loosen the create rule expecting it to control registration; it controls `POST /api/collections/users` instead.
- **`role` can't be set through the data plane.** Even with an owner update rule, the API pins `role` to `user` and ignores any value in the body, so a user can't promote themselves. Roles only change via the superadmin path.
- **Own records means "my own account" here.** On every other collection the preset compares an owner field against the caller (`record.<ownerField> = auth.id`). A user record has no relation pointing at itself, so on `users` the preset resolves to `record.id = auth.id` instead: each user reaches exactly their own record. You therefore need **no** `RELATION → users` field on `users`, the owner field selector is hidden, and a value that happens to be stored there is ignored (and never written into a user record). This is what makes the common setup work — View and Update on **Own records**, everything else locked, and every user can read and edit their own profile and nobody else's.
- **Own records never grants Create on `users`.** At create time there is no record yet whose id could equal the caller's, so the rule cannot be satisfied — including when a client sends its own id in the body. Sign-up goes through `POST /api/auth/register`; if you really want authenticated users to create user records through the data plane, set the create rule to **Signed in**.

## Things worth knowing

- **List rules also filter results**, not just gate the endpoint: an "Own records" list rule only ever returns the calling user's own records, never the full collection — on `users` that is exactly one record, the caller's own. A "Group members" list rule returns the records of the caller's groups, and an empty list for a caller who is in none.
- **Removing a membership takes effect immediately.** The next request no longer sees the records; nothing is cached beyond the request that resolved it.
- **Rules are part of a schema export.** They belong to the collection definition, so the tenant-level schema export and import carry them along — and an import overwrites the rules of an existing collection with whatever the file says. The one exception: if a collection's entry has no `rules` object at all (a hand-edited or generated file), the import keeps the rules that are already there instead of locking the collection, and reports how many it left alone. `fields` and `indexes` work the other way round — they're the point of a schema import, so an entry missing either one is rejected as incomplete and the whole import is refused before anything is written.
- These rules apply to regular tenant-user API calls only. A superadmin browsing this collection from the [Data tab](/admin-ui/collection-data) bypasses them entirely (see [admin bypass](/concepts/roles-and-permissions#admin-bypass-on-tenant-data)) — don't use the admin UI to "test" what a rule allows; call the API as a tenant user instead.
- API clients authenticate with `Authorization: Bearer <accessToken>`, obtained from `POST /api/auth/login` (see [API Reference](/admin-ui/collection-api)).
