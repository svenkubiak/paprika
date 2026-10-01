export const SYSTEM_COLLECTIONS = ['users', 'settings', 'request_logs'] as const

export function isSystemCollection(name: string): boolean {
  return (SYSTEM_COLLECTIONS as readonly string[]).includes(name)
}

// Server-owned users fields: re-injected by the backend, so they cannot be renamed, retyped or
// removed. passwordHash/passwordSalt never appear in the schema but must not be re-added either.
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
