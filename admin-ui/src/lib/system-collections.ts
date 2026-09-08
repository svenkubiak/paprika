export const SYSTEM_COLLECTIONS = ['users', 'settings', 'request_logs'] as const

export function isSystemCollection(name: string): boolean {
  return (SYSTEM_COLLECTIONS as readonly string[]).includes(name)
}

// Core, server-owned fields of the users collection. They are re-injected by the backend and
// cannot be renamed, retyped, or removed; the UI renders them as read-only. The internal
// passwordHash/passwordSalt fields never appear in the schema but are guarded here too so admins
// cannot re-add a field with those names.
export const PROTECTED_USER_FIELDS = [
  'username',
  'email',
  'role',
  'password',
  'passwordHash',
  'passwordSalt'
] as const

export function isProtectedUserField(collection: string, name: string): boolean {
  return (
    collection === 'users' &&
    (PROTECTED_USER_FIELDS as readonly string[]).includes(name.trim())
  )
}

export function requestDeleteCollection(collection: string) {
  window.dispatchEvent(
    new CustomEvent('paprika:delete-collection', { detail: { collection } })
  )
}
