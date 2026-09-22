/**
 * Turning a record into the state of a schema-driven form and back.
 *
 * This lives outside the components because two editors need exactly the same behaviour: the
 * generic record editor of a collection and the user editor, which has to offer the custom fields
 * a tenant added to its users schema. Keeping the conversion in one place is what keeps "what the
 * form shows" and "what is sent" from drifting apart between the two.
 */
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
 * Per DATETIME field the value as stored and the picker value derived from it. Opening and saving
 * a record without touching the field must not rewrite its timestamp - a different offset or a
 * dropped fraction of a second would be a silent data change caused by merely looking at it.
 */
export type DateTimeInitial = Record<string, { stored: unknown; input: string }>

export type RecordFormState = {
  /** Dynamic field widgets narrow values at runtime based on the collection schema. */
  values: Record<string, any>
  /** JSON fields are edited as text, so the raw text is kept next to the values. */
  jsonText: Record<string, string>
  dateTimeInitial: DateTimeInitial
}

/**
 * A new record starts with an empty string for every field, so "no value" and "explicitly empty"
 * cannot be told apart here - which is fine, because the pre-fill only ever runs while the form is
 * being populated from the record, never while the user types.
 */
export function isEmptyValue(value: unknown): boolean {
  return value === null || value === undefined || (typeof value === 'string' && !value.trim())
}

export function serializeJsonFieldText(value: unknown): string {
  if (value === null || value === undefined) return ''
  return JSON.stringify(value, null, 2)
}

/** Builds the form state for the given fields. Keys outside the schema are ignored. */
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
        // New records start at "now" as a convenience; the clear button next to the picker puts
        // the field back to empty. The initial pair above stays at the stored (empty) value, so
        // the pre-filled timestamp counts as a change and is serialized on save.
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
 * Form state -> the values to send. Throws an `Error` with a message meant for the user when a
 * value cannot be converted at all (a number that is not one, invalid JSON).
 *
 * FILE fields are never part of the result: their content travels as multipart, and sending the
 * stored descriptor back would overwrite it with a copy of itself.
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
        // Unchanged picker value: keep the stored string byte for byte, including fractions of a
        // second and an offset this browser would not have produced.
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
