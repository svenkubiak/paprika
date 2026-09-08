# Collection: API Reference

`/admin/collections/:collection/api` — auto-generated, always-up-to-date REST and realtime documentation for this specific collection, built directly from its schema. There's nothing to configure here; it's a reference view.

## Endpoints

Expandable cards for each operation (`GET` list, `POST` create, `GET` by id, `PATCH` update, `DELETE`), each showing:

- The exact request shape — JSON body for scalar fields, plus a separate multipart example whenever the collection has `FILE` fields (since files can't be sent as JSON).
- A realistic example response, generated from the collection's actual field names and types.
- The error responses that operation can return (401/403/404/400/502), including validation errors shaped like `[{ "field": "...", "message": "..." }]` and hook-rejection errors (see [Hooks](/admin-ui/collection-hooks)).
- A copy-to-clipboard button for the endpoint path.

If the collection has `FILE` fields, dedicated download/delete endpoints are listed too, one pair per file field.

Every response includes the three [system fields](/concepts/collections#system-fields) (`id`, `createdAt`, `updatedAt`) alongside the schema fields — the reference notes this so it's clear they're not something you define yourself.

## Authentication

A separate card documents `/api/auth/register`, `/api/auth/login`, and `/api/auth/refresh` — the tenant-user auth flow that produces the `Authorization: Bearer <accessToken>` this collection's endpoints expect (unless its [Rules](/admin-ui/collection-rules) allow public access).

The same card also lists the optional recovery endpoints: `/api/auth/password/forgot` and `/api/auth/password/reset`, plus `/api/auth/verify/request` and `/api/auth/verify/confirm`. These are off unless the tenant has [Password reset or email verification](/admin-ui/tenant-users#password-reset-and-email-verification) enabled, and the link is emailed by Paprika over the instance SMTP settings, built from the tenant's configured link URL.

## Realtime (SSE)

A third card documents the Server-Sent Events flow: connect to `GET /api/realtime` to receive a `clientId` from the `connect` event, then `POST /api/realtime/subscribe` with that `clientId` and the collections/records to watch. Once subscribed, record changes arrive as events named after the collection, carrying an `action` (`create`, `update`, or `delete`) and the record. A subscriber only receives events for records their `viewRule` would let them see — the same [rules](/admin-ui/collection-rules) that gate the regular REST API also gate realtime delivery.

## The users collection is a special case

For the tenant [`users` collection](/admin-ui/tenant-users), the reference drops the realtime card. You can't subscribe to `users` and Paprika never broadcasts account changes, so there's nothing live to document. Its REST endpoints also never return `passwordHash` or `passwordSalt`, and passwords are only ever written through the virtual `password` field, so they never show up in an example response either.
