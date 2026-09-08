export const SYSTEM_RECORD_FIELDS = ['id', 'createdAt', 'updatedAt'] as const

export const RESERVED_SCHEMA_FIELD_NAMES = [
  'id',
  'createdAt',
  'updatedAt',
  'created',
  'updated'
] as const

export function isReservedSchemaFieldName(name: string): boolean {
  const normalized = name.trim()
  return (RESERVED_SCHEMA_FIELD_NAMES as readonly string[]).includes(normalized)
}

export const SYSTEM_TIMESTAMP_EXAMPLE = '2026-09-02T10:15:30.123Z'
