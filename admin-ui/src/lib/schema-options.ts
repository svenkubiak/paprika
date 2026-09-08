import type { FieldDefinition, FieldOptions } from '@/types'
import type { SchemaRow } from '@/components/SchemaEditorSheet.vue'
import { parseDefaultValue } from '@/lib/field-validation'

export function parseSelectValues(raw: string): string[] {
  return [
    ...new Set(
      raw
        .split(/[\n,]/)
        .map((value) => value.trim())
        .filter(Boolean)
    )
  ]
}

export function schemaRowToOptions(row: SchemaRow): FieldOptions | null {
  if (row.type === 'RELATION') {
    return {
      collection: row.relationCollection.trim(),
      maxSelect: row.relationMaxSelect,
      cascadeDelete: row.relationCascadeDelete
    }
  }
  if (row.type === 'FILE') {
    return {
      maxSize: row.fileMaxSize,
      mimeTypes: row.fileMimeTypes
        .split(',')
        .map((value) => value.trim())
        .filter(Boolean),
      maxSelect: row.fileMaxSelect
    }
  }
  if (row.type === 'SELECT') {
    return {
      values: parseSelectValues(row.selectValues),
      maxSelect: row.selectMaxSelect
    }
  }
  if (row.type === 'STRING' || row.type === 'EMAIL' || row.type === 'URL') {
    return compactOptions({
      minLength: row.minLength,
      maxLength: row.maxLength,
      pattern: row.pattern.trim() || undefined
    })
  }
  if (row.type === 'NUMBER') {
    return compactOptions({
      numberMin: row.numberMin,
      numberMax: row.numberMax
    })
  }
  if (row.type === 'JSON') {
    return compactOptions({
      maxBytes: row.jsonMaxBytes,
      maxDepth: row.jsonMaxDepth,
      onlyObject: row.jsonOnlyObject || undefined,
      onlyArray: row.jsonOnlyArray || undefined
    })
  }
  if (row.type === 'DATE') {
    return compactOptions({
      minDate: row.minDate.trim() || undefined,
      maxDate: row.maxDate.trim() || undefined
    })
  }
  if (row.type === 'TIME') {
    return compactOptions({
      minTime: row.minTime.trim() || undefined,
      maxTime: row.maxTime.trim() || undefined
    })
  }
  if (row.type === 'DATETIME') {
    return compactOptions({
      minDateTime: row.minDateTime.trim() || undefined,
      maxDateTime: row.maxDateTime.trim() || undefined
    })
  }
  return null
}

export function schemaRowToField(row: SchemaRow): FieldDefinition {
  const name = row.name.trim()
  let defaultValue: unknown
  if (
    row.type !== 'RELATION' &&
    row.type !== 'FILE' &&
    row.defaultValue.trim()
  ) {
    defaultValue = parseDefaultValue(row.defaultValue, row.type)
  }
  return {
    name,
    type: row.type,
    required: row.required,
    nullable: !row.required,
    options: schemaRowToOptions(row),
    default: defaultValue
  }
}

function compactOptions(options: FieldOptions): FieldOptions | null {
  const entries = Object.entries(options).filter(([, value]) => value !== undefined && value !== null && value !== '')
  return entries.length > 0 ? Object.fromEntries(entries) : null
}

export function optionsToSchemaRow(
  field: FieldDefinition
): Omit<
  SchemaRow,
  'name' | 'type' | 'required' | 'persisted' | 'indexEnabled' | 'indexDirection' | 'indexUnique'
> {
  const options = field.options || {}
  return {
    defaultValue:
      field.type === 'RELATION' || field.type === 'FILE' || field.default === undefined
        ? ''
        : typeof field.default === 'object'
          ? JSON.stringify(field.default)
          : String(field.default),
    relationCollection: options.collection || '',
    relationMaxSelect: options.maxSelect ?? 1,
    relationCascadeDelete: options.cascadeDelete ?? false,
    fileMaxSize: options.maxSize ?? 5 * 1024 * 1024,
    fileMimeTypes: (options.mimeTypes || []).join(', '),
    fileMaxSelect: options.maxSelect ?? 1,
    selectValues: (options.values || []).join('\n'),
    selectMaxSelect: options.maxSelect ?? 1,
    minLength: options.minLength,
    maxLength: options.maxLength,
    pattern: options.pattern || '',
    numberMin: options.numberMin,
    numberMax: options.numberMax,
    jsonMaxBytes: options.maxBytes,
    jsonMaxDepth: options.maxDepth,
    jsonOnlyObject: options.onlyObject ?? false,
    jsonOnlyArray: options.onlyArray ?? false,
    minDate: options.minDate || '',
    maxDate: options.maxDate || '',
    minTime: options.minTime || '',
    maxTime: options.maxTime || '',
    minDateTime: options.minDateTime || '',
    maxDateTime: options.maxDateTime || ''
  }
}
