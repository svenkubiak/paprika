import type { FieldDefinition } from '@/types'
import {
  booleanToFormValue,
  selectToFormValue,
  serializeBooleanFieldValue,
  serializeSelectFieldValue,
  unsetFieldValue
} from '@/lib/field-validation'
import {
  currentDateInputValue,
  currentDateTimeInputValue,
  toDateTimeInputValue,
  toDateTimeStoredValue
} from '@/lib/datetime'

export type FormMode = 'new' | 'edit'

/**
 * Opening and saving a record without touching a DATETIME field must not rewrite it (offset,
 * fractions of a second), so the stored value is kept next to the derived picker value.
 */
export type DateTimeInitial = Record<string, { stored: unknown; input: string }>

export type RecordFormState = {
  /** Dynamic field widgets narrow values at runtime based on the collection schema. */
  values: Record<string, any>
  /** JSON fields are edited as text. */
  jsonText: Record<string, string>
  dateTimeInitial: DateTimeInitial
}

/**
 * Treats '' as empty: safe because the pre-fill only runs while the form is being populated,
 * never while the user types.
 */
export function isEmptyValue(value: unknown): boolean {
  return value === null || value === undefined || (typeof value === 'string' && !value.trim())
}

export function serializeJsonFieldText(value: unknown): string {
  if (value === null || value === undefined) return ''
  return JSON.stringify(value, null, 2)
}

export function buildRecordFormState(
  record: Record<string, unknown>,
  fields: FieldDefinition[],
  mode: FormMode
): RecordFormState {
  const state: RecordFormState = { values: {}, jsonText: {}, dateTimeInitial: {} }

  for (const field of fields) {
    const stored = record[field.name]

    switch (field.type) {
      case 'JSON':
        state.jsonText[field.name] = serializeJsonFieldText(stored)
        break
      case 'BOOLEAN':
        state.values[field.name] = booleanToFormValue(stored)
        break
      case 'SELECT':
        state.values[field.name] = selectToFormValue(field, stored)
        break
      case 'RELATION':
        state.values[field.name] =
          (field.options?.maxSelect || 1) > 1 && Array.isArray(stored)
            ? (stored as string[]).join(', ')
            : (stored ?? '')
        break
      case 'DATETIME': {
        const input = toDateTimeInputValue(stored)
        state.dateTimeInitial[field.name] = { stored, input }
        // New records start at "now". The initial pair stays at the stored (empty) value, so the
        // pre-filled timestamp counts as a change and is serialized on save.
        state.values[field.name] =
          !input && mode === 'new' && isEmptyValue(stored) ? currentDateTimeInputValue() : input
        break
      }
      case 'DATE': {
        const input = typeof stored === 'string' ? stored : ''
        state.values[field.name] =
          !input && mode === 'new' && isEmptyValue(stored) ? currentDateInputValue() : input
        break
      }
      case 'TIME':
        state.values[field.name] = typeof stored === 'string' ? stored : ''
        break
      case 'FILE':
        state.values[field.name] = stored
        break
      default:
        state.values[field.name] = stored ?? ''
    }
  }

  return state
}

/**
 * Throws an `Error` with a user-facing message when a value cannot be converted. FILE fields are
 * never included: sending the stored descriptor back would overwrite it with a copy of itself.
 */
export function serializeRecordForm(
  fields: FieldDefinition[],
  state: RecordFormState,
  mode: FormMode
): Record<string, unknown> {
  const values: Record<string, unknown> = {}

  for (const field of fields) {
    const raw = state.values[field.name]

    switch (field.type) {
      case 'FILE':
        break

      case 'NUMBER': {
        if (raw === '' || raw === null || raw === undefined) {
          assign(values, field, unsetFieldValue(field, mode))
          break
        }
        const parsed = Number(raw)
        if (Number.isNaN(parsed)) {
          throw new Error(`${field.name} must be a number`)
        }
        values[field.name] = parsed
        break
      }

      case 'BOOLEAN':
        assign(values, field, serializeBooleanFieldValue(raw, field, mode))
        break

      case 'SELECT':
        assign(values, field, serializeSelectFieldValue(raw, field, mode))
        break

      case 'RELATION': {
        if ((field.options?.maxSelect || 1) > 1) {
          if (raw === '' || raw === null || raw === undefined) {
            assign(values, field, unsetFieldValue(field, mode))
          } else if (typeof raw === 'string') {
            values[field.name] = raw
              .split(',')
              .map((item) => item.trim())
              .filter(Boolean)
          } else {
            values[field.name] = raw
          }
          break
        }
        if (typeof raw !== 'string' || !raw.trim()) {
          assign(values, field, unsetFieldValue(field, mode))
        } else {
          values[field.name] = raw
        }
        break
      }

      case 'DATE':
      case 'TIME': {
        if (typeof raw !== 'string' || !raw.trim()) {
          assign(values, field, unsetFieldValue(field, mode))
        } else {
          values[field.name] = raw
        }
        break
      }

      case 'DATETIME': {
        const input = typeof raw === 'string' ? raw : ''
        const initial = state.dateTimeInitial[field.name]
        // Unchanged picker value: keep the stored string byte for byte (fractions, foreign offset).
        let serialized: unknown
        if (initial && input === initial.input) {
          serialized =
            typeof initial.stored === 'string' && initial.stored.trim()
              ? initial.stored
              : unsetFieldValue(field, mode)
        } else {
          serialized = toDateTimeStoredValue(input) ?? unsetFieldValue(field, mode)
        }
        assign(values, field, serialized)
        break
      }

      case 'JSON': {
        const text = state.jsonText[field.name]?.trim()
        if (!text) {
          if (field.required) {
            throw new Error(`JSON field "${field.name}" is required`)
          }
          assign(values, field, unsetFieldValue(field, mode))
          break
        }
        try {
          values[field.name] = JSON.parse(text)
        } catch {
          throw new Error(`JSON field "${field.name}" is not valid JSON`)
        }
        break
      }

      default: {
        if (typeof raw === 'string' && !raw.trim()) {
          assign(values, field, unsetFieldValue(field, mode))
        } else if (raw !== undefined) {
          values[field.name] = raw
        }
      }
    }
  }

  return values
}

/** `undefined` means "omit the field entirely", `null` means "clear it" - see unsetFieldValue. */
function assign(values: Record<string, unknown>, field: FieldDefinition, value: unknown) {
  if (value === undefined) {
    return
  }
  values[field.name] = value
}
