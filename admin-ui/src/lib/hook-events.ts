import type { HookEvent } from '@/types'
import { isSystemCollection } from '@/lib/system-collections'

export interface HookEventOption {
  label: string
  value: HookEvent
}

// CRUD lifecycle events available on every collection.
export const CRUD_HOOK_EVENTS: HookEventOption[] = [
  { label: 'Before list', value: 'beforeList' },
  { label: 'Before view', value: 'beforeView' },
  { label: 'Before create', value: 'beforeCreate' },
  { label: 'After create', value: 'afterCreate' },
  { label: 'Before update', value: 'beforeUpdate' },
  { label: 'After update', value: 'afterUpdate' },
  { label: 'Before delete', value: 'beforeDelete' },
  { label: 'After delete', value: 'afterDelete' }
]

// Auth lifecycle events, only valid on the tenant users collection.
export const AUTH_HOOK_EVENTS: HookEventOption[] = [
  { label: 'Before register', value: 'beforeRegister' },
  { label: 'After register', value: 'afterRegister' },
  { label: 'Before login', value: 'beforeLogin' },
  { label: 'After login', value: 'afterLogin' },
  { label: 'Before refresh', value: 'beforeRefresh' },
  { label: 'After refresh', value: 'afterRefresh' }
]

export function isUsersCollection(collection: string): boolean {
  return collection === 'users'
}

/** Returns the hook events selectable for a given collection. */
export function hookEventOptions(collection: string): HookEventOption[] {
  if (isUsersCollection(collection)) {
    return [...CRUD_HOOK_EVENTS, ...AUTH_HOOK_EVENTS]
  }
  return CRUD_HOOK_EVENTS
}

export function hookEventLabel(event: HookEvent): string {
  return (
    [...CRUD_HOOK_EVENTS, ...AUTH_HOOK_EVENTS].find((option) => option.value === event)?.label ??
    event
  )
}

// Re-exported so callers importing from this module keep a single source of truth.
export { isSystemCollection }
