import type { FieldDefinition } from '@/types'

/** Sentinel for optional boolean fields without a value in the record editor. */
export const BOOLEAN_UNSET = '__boolean_unset__'

/** Sentinel for optional select fields without a value in the record editor. */
export const SELECT_UNSET = '__select_unset__'

/** Mirrors LocalDate.parse of DateFieldValidator. */
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

/** Mirrors LocalTime.parse of TimeFieldValidator: HH:mm, HH:mm:ss, optional fraction. */
const ISO_TIME = /^([01]\d|2[0-3]):[0-5]\d(:[0-5]\d(\.\d{1,9})?)?$/

/** Mirrors OffsetDateTime.parse of DateTimeFieldValidator - the offset is mandatory. */
const ISO_OFFSET_DATETIME =
  /^\d{4}-\d{2}-\d{2}T([01]\d|2[0-3]):[0-5]\d(:[0-5]\d(\.\d{1,9})?)?(Z|[+-]([01]\d|2[0-3]):[0-5]\d)$/

/** True when the yyyy-MM-dd text is also a real calendar date, as LocalDate.parse requires. */
function isRealDate(text: string): boolean {
  const [year, month, day] = text.split('-').map(Number)
  const date = new Date(Date.UTC(year, month - 1, day))
  return (
    date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
  )
}

export type FieldValidationIssue = {
  field: string
  message: string
}

function options(field: FieldDefinition) {
  return field.options || {}
}

function validateTextConstraints(field: FieldDefinition, value: string, issues: FieldValidationIssue[]) {
  const opts = options(field)
  if (opts.minLength != null && value.length < opts.minLength) {
    issues.push({ field: field.name, message: 'Value is shorter than minLength' })
  }
  if (opts.maxLength != null && value.length > opts.maxLength) {
    issues.push({ field: field.name, message: 'Value exceeds maxLength' })
  }
  if (opts.pattern) {
    try {
      if (!new RegExp(opts.pattern).test(value)) {
        issues.push({ field: field.name, message: 'Value does not match pattern' })
      }
    } catch {
      issues.push({ field: field.name, message: 'Invalid pattern configured for field' })
    }
  }
}

function validateNumberConstraints(field: FieldDefinition, value: number, issues: FieldValidationIssue[]) {
  const opts = options(field)
  if (opts.numberMin != null && value < opts.numberMin) {
    issues.push({ field: field.name, message: 'Value is less than minimum' })
  }
  if (opts.numberMax != null && value > opts.numberMax) {
    issues.push({ field: field.name, message: 'Value exceeds maximum' })
  }
}

function jsonDepth(value: unknown, current = 1): number {
  if (value == null || typeof value !== 'object') return current
  if (Array.isArray(value)) {
    if (value.length === 0) return current
    return Math.max(...value.map((item) => jsonDepth(item, current + 1)))
  }
  const values = Object.values(value as Record<string, unknown>)
  if (values.length === 0) return current
  return Math.max(...values.map((item) => jsonDepth(item, current + 1)))
}

function utf8Length(value: unknown): number {
  return new TextEncoder().encode(JSON.stringify(value)).length
}

function validateJsonConstraints(field: FieldDefinition, value: unknown, issues: FieldValidationIssue[]) {
  const opts = options(field)
  if (opts.onlyObject && (typeof value !== 'object' || value === null || Array.isArray(value))) {
    issues.push({ field: field.name, message: 'Expected JSON object' })
  }
  if (opts.onlyArray && !Array.isArray(value)) {
    issues.push({ field: field.name, message: 'Expected JSON array' })
  }
  if (opts.maxDepth != null && jsonDepth(value) > opts.maxDepth) {
    issues.push({ field: field.name, message: 'JSON exceeds maxDepth' })
  }
  if (opts.maxBytes != null && utf8Length(value) > opts.maxBytes) {
    issues.push({ field: field.name, message: 'JSON exceeds maxBytes' })
  }
}

export function validateFieldValue(field: FieldDefinition, value: unknown): string | null {
  const issues: FieldValidationIssue[] = []
  const empty =
    value === null ||
    value === undefined ||
    value === '' ||
    value === BOOLEAN_UNSET ||
    value === SELECT_UNSET ||
    (field.type === 'SELECT' && Array.isArray(value) && value.length === 0)

  if (empty) {
    return field.required ? `${field.name} is required` : null
  }

  switch (field.type) {
    case 'STRING': {
      if (typeof value !== 'string') return `${field.name} must be a string`
      validateTextConstraints(field, value, issues)
      break
    }
    case 'EMAIL': {
      if (typeof value !== 'string') return `${field.name} must be an email`
      const at = value.indexOf('@')
      if (at <= 0 || at !== value.lastIndexOf('@') || at === value.length - 1) {
        issues.push({ field: field.name, message: 'Invalid email address' })
      }
      validateTextConstraints(field, value, issues)
      break
    }
    case 'URL': {
      if (typeof value !== 'string') return `${field.name} must be a URL`
      try {
        const url = new URL(value)
        if (!url.protocol || !url.hostname) {
          issues.push({ field: field.name, message: 'Invalid URL' })
        }
      } catch {
        issues.push({ field: field.name, message: 'Invalid URL' })
      }
      validateTextConstraints(field, value, issues)
      break
    }
    case 'NUMBER': {
      if (typeof value !== 'number' || Number.isNaN(value)) return `${field.name} must be a number`
      validateNumberConstraints(field, value, issues)
      break
    }
    case 'BOOLEAN': {
      if (typeof value !== 'boolean') return `${field.name} must be a boolean`
      break
    }
    case 'DATE': {
      if (typeof value !== 'string') return `${field.name} must be a string`
      if (!ISO_DATE.test(value) || !isRealDate(value)) {
        return `${field.name}: Expected ISO date (yyyy-MM-dd)`
      }
      validateTextConstraints(field, value, issues)
      break
    }
    case 'TIME': {
      if (typeof value !== 'string') return `${field.name} must be a string`
      if (!ISO_TIME.test(value)) {
        return `${field.name}: Expected ISO time`
      }
      validateTextConstraints(field, value, issues)
      break
    }
    case 'DATETIME': {
      if (typeof value !== 'string') return `${field.name} must be a string`
      if (!ISO_OFFSET_DATETIME.test(value)) {
        return `${field.name}: Expected ISO datetime including timezone`
      }
      validateTextConstraints(field, value, issues)
      break
    }
    case 'JSON': {
      if (typeof value !== 'object' || value === null) return `${field.name} must be a JSON object or array`
      validateJsonConstraints(field, value, issues)
      break
    }
    case 'SELECT': {
      const allowed = options(field).values || []
      const maxSelect = options(field).maxSelect || 1
      if (maxSelect <= 1) {
        if (typeof value !== 'string' || !allowed.includes(value)) {
          issues.push({ field: field.name, message: 'Value is not an allowed option' })
        }
      } else if (!Array.isArray(value)) {
        return `${field.name} must be an array of allowed options`
      } else {
        if (value.length > maxSelect) issues.push({ field: field.name, message: 'Too many selected values' })
        const seen = new Set<string>()
        for (const item of value) {
          if (typeof item !== 'string' || !allowed.includes(item) || seen.has(item)) {
            issues.push({ field: field.name, message: 'Invalid selected values' })
            break
          }
          seen.add(item)
        }
      }
      break
    }
    case 'RELATION': {
      const maxSelect = options(field).maxSelect || 1
      if (maxSelect <= 1) {
        if (typeof value !== 'string' || !value.trim()) {
          issues.push({ field: field.name, message: 'Expected relation id' })
        }
      } else if (!Array.isArray(value) || value.some((item) => typeof item !== 'string' || !String(item).trim())) {
        issues.push({ field: field.name, message: 'Expected array of relation ids' })
      } else if (value.length > maxSelect) {
        issues.push({ field: field.name, message: 'Too many relation ids' })
      }
      break
    }
    case 'FILE':
      break
  }

  return issues[0]?.message || null
}

export function validateRecordValues(
  fields: FieldDefinition[],
  values: Record<string, unknown>
): FieldValidationIssue[] {
  const issues: FieldValidationIssue[] = []
  for (const field of fields) {
    const message = validateFieldValue(field, values[field.name])
    if (message) issues.push({ field: field.name, message })
  }
  return issues
}

export function parseDefaultValue(raw: string, type: FieldDefinition['type']): unknown {
  const trimmed = raw.trim()
  if (!trimmed) return undefined
  if (type === 'NUMBER') return Number(trimmed)
  if (type === 'BOOLEAN') {
    if (trimmed !== 'true' && trimmed !== 'false') {
      throw new Error('Boolean default must be "true" or "false"')
    }
    return trimmed === 'true'
  }
  if (type === 'JSON' || type === 'SELECT' || type === 'RELATION') {
    return JSON.parse(trimmed)
  }
  return trimmed
}

export function booleanToFormValue(value: unknown): string {
  if (value === true || value === 'true') return 'true'
  if (value === false || value === 'false') return 'false'
  return BOOLEAN_UNSET
}

export function booleanFieldSelectItems(field: FieldDefinition) {
  const items = [
    { label: 'No value', value: BOOLEAN_UNSET },
    { label: 'true', value: 'true' },
    { label: 'false', value: 'false' }
  ]
  return field.required ? items.filter((item) => item.value !== BOOLEAN_UNSET) : items
}

export function serializeBooleanFieldValue(
  raw: unknown,
  field: FieldDefinition,
  mode: 'new' | 'edit'
): unknown {
  if (raw === BOOLEAN_UNSET || raw === undefined || raw === null || raw === '') {
    return unsetFieldValue(field, mode)
  }
  return raw === true || raw === 'true'
}

/**
 * What an emptied field sends: `null` clears it on update, `undefined` omits it on create.
 * Never `''` - an empty string reaches the type validators and is rejected there, while a missing
 * value is correctly treated as "not set" (ValidationService only checks `required` for it).
 */
export function unsetFieldValue(field: FieldDefinition, mode: 'new' | 'edit'): null | undefined {
  return mode === 'edit' && !field.required ? null : undefined
}

/**
 * Items for a select field: an optional single-select gets the same "No value" entry a boolean
 * field has, because otherwise a value once chosen can never be taken back through the form.
 * A required field does not get it, and a multi-select clears by deselecting everything.
 */
export function selectFieldSelectItems(field: FieldDefinition) {
  const items = (field.options?.values || []).map((value) => ({ label: value, value }))
  if (field.required || (field.options?.maxSelect || 1) > 1) {
    return items
  }
  return [{ label: 'No value', value: SELECT_UNSET }, ...items]
}

export function selectToFormValue(field: FieldDefinition, value: unknown): unknown {
  if ((field.options?.maxSelect || 1) > 1) {
    return Array.isArray(value) ? value : []
  }
  if (typeof value === 'string' && value !== '') {
    return value
  }
  return field.required ? undefined : SELECT_UNSET
}

export function serializeSelectFieldValue(
  raw: unknown,
  field: FieldDefinition,
  mode: 'new' | 'edit'
): unknown {
  if ((field.options?.maxSelect || 1) > 1) {
    const values = Array.isArray(raw) ? raw.filter((value) => typeof value === 'string' && value !== '') : []
    return values.length > 0 ? values : unsetFieldValue(field, mode)
  }
  if (raw === SELECT_UNSET || raw === undefined || raw === null || raw === '') {
    return unsetFieldValue(field, mode)
  }
  return raw
}

export function applyDefaultsToRecord(
  fields: FieldDefinition[],
  values: Record<string, unknown>
): Record<string, unknown> {
  const next = { ...values }
  for (const field of fields) {
    if (field.default === undefined || field.type === 'FILE') continue
    if (!(field.name in next) || next[field.name] === undefined) {
      next[field.name] = field.default
    }
  }
  return next
}
