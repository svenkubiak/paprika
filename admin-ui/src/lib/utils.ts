import type { FieldType, RuleLevel } from '@/types'

export function normalizeSlug(value: string): string {
  return value.trim().toLowerCase().replace(/\s+/g, '-').replace(/[^a-z0-9-]/g, '')
}

export function toCamelCaseFieldName(value: string): string {
  const trimmed = value.trim()
  if (!trimmed) return ''

  if (!/[-_\s]/.test(trimmed)) {
    return trimmed.charAt(0).toLowerCase() + trimmed.slice(1)
  }

  return trimmed
    .toLowerCase()
    .replace(/[-_\s]+([a-z0-9])/g, (_, char: string) => char.toUpperCase())
}

export function fieldDisplayName(value: string): string {
  const camel = toCamelCaseFieldName(value)
  if (!camel) return ''
  return camel.charAt(0).toUpperCase() + camel.slice(1)
}

/** Sentinel for USelect options — SelectItem cannot use an empty string value. */
export const SELECT_EMPTY = '__empty__'

const SECRET_CHARS = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'

export function generateRandomSecret(length = 32): string {
  const bytes = new Uint8Array(length)
  crypto.getRandomValues(bytes)
  return Array.from(bytes, (byte) => SECRET_CHARS[byte % SECRET_CHARS.length]).join('')
}

/** Header names are case-insensitive, so the backend stores and compares them lowercased. */
export function parseForwardHeaders(value: string): string[] {
  return value
    .split(',')
    .map((name) => name.trim().toLowerCase())
    .filter((name) => name.length > 0)
}

export function ruleLevel(rule: string | null | undefined): RuleLevel {
  if (!rule?.trim()) return ''
  const normalized = rule.trim().toLowerCase()
  if (normalized === '*') return '*'
  if (normalized === 'auth') return 'auth'
  if (normalized === 'owner') return 'owner'
  if (normalized === 'group') return 'group'
  if (normalized === 'peers') return 'peers'
  return ''
}

export function ruleValueFromLevel(level: RuleLevel): string | null {
  if (level === '') return null
  return level
}

/** The two presets that decide access through a membership in a second collection. */
export function isMembershipLevel(level: string | null | undefined): boolean {
  return level === 'group' || level === 'peers'
}

export function ruleLevelForSelect(rule: string | null | undefined): RuleLevel | typeof SELECT_EMPTY {
  const level = ruleLevel(rule)
  return level === '' ? SELECT_EMPTY : level
}

export function ruleLevelFromSelect(
  level: RuleLevel | typeof SELECT_EMPTY
): RuleLevel {
  return level === SELECT_EMPTY ? '' : level
}

export function fieldExample(type: string): string {
  switch (type) {
    case 'STRING':
    case 'EMAIL':
    case 'URL':
    case 'DATE':
    case 'TIME':
    case 'DATETIME':
    case 'RELATION':
      return '"example"'
    case 'FILE':
      return '{ "id": "…", "name": "photo.jpg", "mimeType": "image/jpeg", "size": 12345 }'
    case 'JSON':
      return '{ "key": "value" }'
    case 'SELECT':
      return '"draft"'
    case 'NUMBER':
      return '42'
    case 'BOOLEAN':
      return 'true'
    default:
      return '"example"'
  }
}

export function formatCellValue(value: unknown, type?: string): string {
  if (value === null || value === undefined || value === '') return '—'
  if (type === 'BOOLEAN') return value ? 'yes' : 'no'
  if (type === 'FILE') {
    if (Array.isArray(value)) return `${value.length} file(s)`
    if (typeof value === 'object' && value !== null && 'name' in value) {
      return String((value as { name?: string }).name || 'file')
    }
  }
  if (type === 'JSON' && typeof value === 'object' && value !== null) {
    const text = JSON.stringify(value)
    return text.length > 80 ? `${text.slice(0, 77)}…` : text
  }
  if (type === 'SELECT' && Array.isArray(value)) {
    return value.join(', ')
  }
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

export function copyToClipboard(text: string): Promise<void> {
  return navigator.clipboard.writeText(text)
}

export const FIELD_TYPES = [
  'STRING',
  'NUMBER',
  'BOOLEAN',
  'EMAIL',
  'URL',
  'DATE',
  'TIME',
  'DATETIME',
  'RELATION',
  'FILE',
  'JSON',
  'SELECT'
] as const

export const FIELD_TYPE_META: Record<
  FieldType,
  { label: string; icon: string }
> = {
  STRING: { label: 'String', icon: 'i-lucide-type' },
  NUMBER: { label: 'Number', icon: 'i-lucide-hash' },
  BOOLEAN: { label: 'Boolean', icon: 'i-lucide-toggle-left' },
  EMAIL: { label: 'Email', icon: 'i-lucide-mail' },
  URL: { label: 'Url', icon: 'i-lucide-link' },
  DATE: { label: 'Date', icon: 'i-lucide-calendar' },
  TIME: { label: 'Time', icon: 'i-lucide-clock' },
  DATETIME: { label: 'Datetime', icon: 'i-lucide-calendar-clock' },
  RELATION: { label: 'Relation', icon: 'i-lucide-git-branch' },
  FILE: { label: 'File', icon: 'i-lucide-paperclip' },
  JSON: { label: 'Json', icon: 'i-lucide-braces' },
  SELECT: { label: 'Select', icon: 'i-lucide-list-checks' }
}

export function fieldTypeLabel(type: FieldType): string {
  return FIELD_TYPE_META[type]?.label ?? type
}

export function fieldTypeIcon(type: FieldType): string {
  return FIELD_TYPE_META[type]?.icon ?? 'i-lucide-circle-question-mark'
}

/**
 * The schema editor offers "String" and "Text" as two entries, but both write the same
 * `STRING` field type - they only differ in `options.multiline`, which decides whether the record
 * editor renders a single-line input or a textarea. It is a presentation question, not a type
 * question, so it stays out of FieldType.
 */
export const STRING_MULTILINE_CHOICE = 'STRING_MULTILINE'

export type FieldTypeChoice = FieldType | typeof STRING_MULTILINE_CHOICE

export const FIELD_TYPE_CHOICES: {
  value: FieldTypeChoice
  label: string
  icon: string
  type: FieldType
  multiline: boolean
}[] = FIELD_TYPES.flatMap((type) => {
  const entry = {
    value: type as FieldTypeChoice,
    label: FIELD_TYPE_META[type].label,
    icon: FIELD_TYPE_META[type].icon,
    type: type as FieldType,
    multiline: false
  }
  if (type !== 'STRING') {
    return [entry]
  }
  return [
    entry,
    {
      value: STRING_MULTILINE_CHOICE as FieldTypeChoice,
      label: 'Text',
      icon: 'i-lucide-align-left',
      type: 'STRING' as FieldType,
      multiline: true
    }
  ]
})

export function fieldTypeChoiceSelectItems() {
  return FIELD_TYPE_CHOICES.map((choice) => ({
    label: choice.label,
    value: choice.value,
    icon: choice.icon
  }))
}

export function fieldTypeChoice(type: FieldType, multiline?: boolean): FieldTypeChoice {
  return type === 'STRING' && multiline ? STRING_MULTILINE_CHOICE : type
}

function choiceMeta(choice: FieldTypeChoice) {
  return FIELD_TYPE_CHOICES.find((entry) => entry.value === choice)
}

export function fieldTypeChoiceType(choice: FieldTypeChoice): FieldType {
  return choiceMeta(choice)?.type ?? (choice as FieldType)
}

export function fieldTypeChoiceMultiline(choice: FieldTypeChoice): boolean {
  return choiceMeta(choice)?.multiline ?? false
}

export function fieldTypeChoiceLabel(type: FieldType, multiline?: boolean): string {
  return choiceMeta(fieldTypeChoice(type, multiline))?.label ?? fieldTypeLabel(type)
}

export function fieldTypeChoiceIcon(type: FieldType, multiline?: boolean): string {
  return choiceMeta(fieldTypeChoice(type, multiline))?.icon ?? fieldTypeIcon(type)
}

export const RULE_PRESETS = {
  locked: {
    listRule: null,
    viewRule: null,
    createRule: null,
    updateRule: null,
    deleteRule: null
  },
  public: {
    listRule: '*',
    viewRule: '*',
    createRule: '*',
    updateRule: '*',
    deleteRule: '*'
  },
  auth: {
    listRule: 'auth',
    viewRule: 'auth',
    createRule: 'auth',
    updateRule: 'auth',
    deleteRule: 'auth'
  },
  owner: {
    listRule: 'owner',
    viewRule: 'owner',
    createRule: 'auth',
    updateRule: 'owner',
    deleteRule: 'owner'
  },
  group: {
    listRule: 'group',
    viewRule: 'group',
    createRule: 'group',
    updateRule: 'group',
    deleteRule: 'group'
  },
  // Create is never granted by "peers" - there is no record yet whose identity could be checked,
  // and sign-up goes through POST /api/auth/register.
  peers: {
    listRule: 'peers',
    viewRule: 'peers',
    createRule: null,
    updateRule: 'peers',
    deleteRule: 'peers'
  }
} as const
