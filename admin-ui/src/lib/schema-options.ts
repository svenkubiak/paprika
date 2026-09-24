import type { FieldDefinition, FieldOptions } from '@/types'
import type { SchemaRow } from '@/components/SchemaEditorSheet.vue'
import { parseDefaultValue } from '@/lib/field-validation'

/** Mirrors FieldOptions.MAX_IMAGE_WIDTHS on the server. */
export const MAX_IMAGE_WIDTHS = 4

/**
 * Mirrors FieldOptions.DEFAULT_MAX_SIZE on the server: four million bytes, kept under the
 * 4 MiB the server accepts as a whole request body so that boundaries, part headers and the
 * other fields of the same upload still fit.
 */
export const DEFAULT_FILE_MAX_SIZE = 4_000_000

/** Mirrors FieldOptions.MAX_IMAGE_WIDTH on the server. */
export const MAX_IMAGE_WIDTH = 4096

/** Parses the comma/space separated widths of a file field into normalized, ascending numbers. */
export function parseImageWidths(raw: string): number[] {
  return [
    ...new Set(
      raw
        .split(/[\n,\s]+/)
        .map((value) => value.trim())
        .filter(Boolean)
        .map((value) => Number(value))
    )
  ].sort((a, b) => a - b)
}

/**
 * Returns why the configured widths are rejected, or null when they are fine. The message names
 * both limits, so the editor tells the user what exactly failed - as maxSize and mimeTypes do.
 */
export function validateImageWidths(raw: string): string | null {
  const widths = parseImageWidths(raw)
  if (widths.length === 0) {
    return null
  }
  if (widths.some((width) => !Number.isInteger(width) || width <= 0)) {
    return 'Image widths must be whole numbers greater than 0'
  }
  if (widths.some((width) => width > MAX_IMAGE_WIDTH)) {
    return `Image widths must not exceed ${MAX_IMAGE_WIDTH} px`
  }
  if (widths.length > MAX_IMAGE_WIDTHS) {
    return `A file field may define at most ${MAX_IMAGE_WIDTHS} image widths`
  }
  return null
}

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
    const imageWidths = parseImageWidths(row.fileImageWidths)
    return {
      maxSize: row.fileMaxSize,
      mimeTypes: row.fileMimeTypes
        .split(',')
        .map((value) => value.trim())
        .filter(Boolean),
      maxSelect: row.fileMaxSelect,
      ...(imageWidths.length > 0 ? { imageWidths } : {})
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
      pattern: row.pattern.trim() || undefined,
      // Only STRING may carry it - the server rejects it on every other type, EMAIL and URL
      // included, and they share this options row.
      multiline: row.type === 'STRING' && row.stringMultiline ? true : undefined
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
    fileMaxSize: options.maxSize ?? DEFAULT_FILE_MAX_SIZE,
    fileMimeTypes: (options.mimeTypes || []).join(', '),
    fileMaxSelect: options.maxSelect ?? 1,
    fileImageWidths: (options.imageWidths || []).join(', '),
    selectValues: (options.values || []).join('\n'),
    selectMaxSelect: options.maxSelect ?? 1,
    minLength: options.minLength,
    maxLength: options.maxLength,
    pattern: options.pattern || '',
    stringMultiline: field.type === 'STRING' && options.multiline === true,
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
