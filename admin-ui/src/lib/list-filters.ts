/**
 * The filter builder of the data view, kept free of Vue and of runtime imports so `node --test`
 * runs it as is. Conditions become `filter[<field>][<operator>]=<value>` parameters of the list API.
 */
import type { FieldDefinition, FieldType } from '@/types'

export type FilterOperator =
  | 'eq'
  | 'ne'
  | 'gt'
  | 'gte'
  | 'lt'
  | 'lte'
  | 'in'
  | 'nin'
  | 'contains'
  | 'startsWith'
  | 'null'
  | 'notnull'

export interface FilterCondition {
  id: number
  field: string
  operator: FilterOperator
  /** Scalar operators; for DATETIME the value of a datetime-local input. */
  value: string
  /** in / nin */
  values: string[]
}

/** A field the builder can filter on: a schema field or one of the system fields. */
export interface FilterField {
  name: string
  type: FieldType
  /** SELECT */
  options: string[]
}

// Mirrors ListFilterParser.MAX_CONDITIONS; the server rejects a request with more
export const MAX_FILTER_CONDITIONS = 20

export const OPERATOR_LABELS: Record<FilterOperator, string> = {
  eq: 'is',
  ne: 'is not',
  gt: '>',
  gte: '≥',
  lt: '<',
  lte: '≤',
  in: 'is one of',
  nin: 'is none of',
  contains: 'contains',
  startsWith: 'starts with',
  null: 'has no value',
  notnull: 'has a value'
}

const PRESENCE: FilterOperator[] = ['null', 'notnull']
const ORDER: FilterOperator[] = ['gt', 'gte', 'lt', 'lte']

// What the server allows per type, minus combinations a form does not need (a list of booleans,
// a list of timestamps). First entry is the default when a field is picked.
const OPERATORS_BY_TYPE: Partial<Record<FieldType, FilterOperator[]>> = {
  STRING: ['contains', 'eq', 'ne', 'startsWith', 'in', 'nin', ...PRESENCE],
  EMAIL: ['contains', 'eq', 'ne', 'startsWith', 'in', 'nin', ...PRESENCE],
  URL: ['contains', 'eq', 'ne', 'startsWith', 'in', 'nin', ...PRESENCE],
  SELECT: ['eq', 'ne', 'in', 'nin', 'contains', ...PRESENCE],
  RELATION: ['eq', 'ne', 'in', 'nin', 'contains', ...PRESENCE],
  NUMBER: ['eq', 'ne', ...ORDER, 'in', 'nin', ...PRESENCE],
  DATE: ['eq', 'ne', ...ORDER, ...PRESENCE],
  TIME: ['eq', 'ne', ...ORDER, ...PRESENCE],
  DATETIME: ['gte', 'lt', 'gt', 'lte', 'eq', 'ne', ...PRESENCE],
  BOOLEAN: ['eq', 'ne', ...PRESENCE],
  FILE: [...PRESENCE]
}

/** JSON fields cannot be filtered; id, createdAt and updatedAt can. */
export function filterFields(fields: FieldDefinition[]): FilterField[] {
  const schema = fields
    .filter((field) => OPERATORS_BY_TYPE[field.type] !== undefined)
    .map((field) => ({
      name: field.name,
      type: field.type,
      options: field.type === 'SELECT' ? (field.options?.values ?? []) : []
    }))
  return [
    ...schema,
    { name: 'id', type: 'STRING', options: [] },
    { name: 'createdAt', type: 'DATETIME', options: [] },
    { name: 'updatedAt', type: 'DATETIME', options: [] }
  ]
}

export function operatorsFor(type: FieldType): FilterOperator[] {
  return OPERATORS_BY_TYPE[type] ?? []
}

export function takesNoValue(operator: FilterOperator): boolean {
  return operator === 'null' || operator === 'notnull'
}

export function takesList(operator: FilterOperator): boolean {
  return operator === 'in' || operator === 'nin'
}

/**
 * The condition "Add filter" appends: the first field and operator not used yet, so a second
 * click does not produce a duplicate the server would refuse. Null when every combination is taken.
 */
export function nextCondition(
  id: number,
  fields: FilterField[],
  conditions: FilterCondition[]
): FilterCondition | null {
  for (const field of fields) {
    const operator = operatorsFor(field.type).find(
      (candidate) => !conditions.some((used) => used.field === field.name && used.operator === candidate)
    )
    if (operator) return { id, field: field.name, operator, value: '', values: [] }
  }
  return null
}

/** Keeps the operator when the new field supports it, and drops values that no longer fit. */
export function changeField(condition: FilterCondition, field: FilterField): FilterCondition {
  const operators = operatorsFor(field.type)
  const operator = operators.includes(condition.operator) ? condition.operator : operators[0]
  return { ...condition, field: field.name, operator, value: '', values: [] }
}

/** The problem that keeps a condition from being sent, or null. Incomplete is not a problem. */
export function conditionIssue(
  condition: FilterCondition,
  conditions: FilterCondition[],
  fields: FilterField[]
): string | null {
  const field = fields.find((candidate) => candidate.name === condition.field)
  if (!field) return 'Unknown field'

  // The server refuses a repeated query key, so the same field and operator can only appear once
  const first = conditions.find(
    (other) => other.field === condition.field && other.operator === condition.operator
  )
  if (first && first.id !== condition.id) {
    return `${condition.field} ${OPERATOR_LABELS[condition.operator]} is already used`
  }

  if (field.type === 'NUMBER' && takesList(condition.operator)) {
    const invalid = condition.values.find((value) => !isNumber(value))
    if (invalid !== undefined) return `“${invalid}” is not a number`
  }
  return null
}

export function isComplete(condition: FilterCondition): boolean {
  if (takesNoValue(condition.operator)) return true
  if (takesList(condition.operator)) return condition.values.length > 0
  return condition.value.trim() !== ''
}

/**
 * The query parameters of every complete condition without an issue, in builder order. Values are
 * the API's: lists as JSON arrays, date & time as UTC ISO strings.
 */
export function filterEntries(conditions: FilterCondition[], fields: FilterField[]): [string, string][] {
  const entries: [string, string][] = []
  for (const condition of conditions) {
    if (!isComplete(condition) || conditionIssue(condition, conditions, fields) !== null) continue
    const field = fields.find((candidate) => candidate.name === condition.field)
    if (!field) continue
    entries.push([`filter[${condition.field}][${condition.operator}]`, conditionValue(condition, field)])
  }
  return entries
}

function conditionValue(condition: FilterCondition, field: FilterField): string {
  if (takesNoValue(condition.operator)) return ''
  if (takesList(condition.operator)) {
    const values = field.type === 'NUMBER'
      ? condition.values.map((value) => Number(value))
      : condition.values
    return JSON.stringify(values)
  }
  if (field.type === 'DATETIME') {
    return localDateTimeToIso(condition.value)
  }
  return condition.value.trim()
}

/**
 * A datetime-local input has no offset, which the server requires; it is the browser's local
 * time, so it is converted to UTC here. A value that is already ISO with an offset is kept.
 */
export function localDateTimeToIso(value: string): string {
  const trimmed = value.trim()
  if (/(Z|[+-]\d{2}:\d{2})$/.test(trimmed)) return trimmed
  const date = new Date(trimmed)
  return Number.isNaN(date.getTime()) ? trimmed : date.toISOString()
}

function isNumber(value: string): boolean {
  return value.trim() !== '' && Number.isFinite(Number(value))
}

/** `&key=value…` for the request, every part URL-encoded; empty without entries. */
export function encodeEntries(entries: [string, string][]): string {
  return entries
    .map(([key, value]) => `&${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
    .join('')
}

export interface ListQuery {
  collection: string
  sort: string
  search: string
  filters: [string, string][]
}

/** The list request as a client would send it, URL-encoded and ready to copy. */
export function listRequestPath(query: ListQuery): string {
  const parts: string[] = [`sort=${encodeURIComponent(query.sort)}`]
  if (query.search.trim()) parts.push(`search=${encodeURIComponent(query.search.trim())}`)
  for (const [key, value] of query.filters) {
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
  }
  return `/api/collections/${encodeURIComponent(query.collection)}?${parts.join('&')}`
}

/** The same request for reading: decoded, with only + and & encoded so it stays unambiguous. */
export function readableListRequestPath(query: ListQuery): string {
  const readable = (value: string) => value.replace(/%/g, '%25').replace(/\+/g, '%2B').replace(/&/g, '%26')
  const parts: string[] = [`sort=${readable(query.sort)}`]
  if (query.search.trim()) parts.push(`search=${readable(query.search.trim())}`)
  for (const [key, value] of query.filters) {
    parts.push(`${key}=${readable(value)}`)
  }
  return `/api/collections/${query.collection}?${parts.join('&')}`
}
