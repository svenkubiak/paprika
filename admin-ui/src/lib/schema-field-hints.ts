/**
 * `short` stays visible under the control, `long` sits behind the info toggle. Keep `short` to one
 * sentence; examples, limits and consequences go into `long`.
 */
export type FieldHint = {
  short: string
  long?: string
}

export const schemaFieldHints = {
  name: {
    short: 'API field key stored on each record.',
    long: 'Letters, digits, underscores and hyphens, starting with a letter, under 64 characters.'
  },
  type: {
    short: 'Controls validation, storage, and how the field appears in the record editor.',
    long:
      '"String" and "Text" are the same field type (STRING) with the same validation - "Text" only gets a multi-line input.'
  },
  relationCollection: {
    short: 'Target collection for the stored relation ID(s).',
    long: 'Relations to users always use accounts in the active tenant.'
  },
  fileMaxSize: {
    short: 'Maximum upload size per file.',
    long:
      'The whole request may not exceed 4 MiB, so several files in one request have to fit into that together.'
  },
  fileMimeTypes: {
    short: 'Allow list of MIME types. Leave empty to allow any type.',
    long: 'Examples: image/jpeg, image/png, or image/* for any image type.'
  },
  fileMaxSelect: {
    short: 'Maximum number of files stored on one record. Use 1 for a single file field.'
  },
  fileImageWidths: {
    short: 'Extra image widths kept per upload, at most 4 and at most 4096 px each.',
    long:
      'Only JPEG, PNG and GIF are scaled; images narrower than a width keep the original for it. Clients request one with ?width=320. Images above 30 megapixels are rejected, because scaling has to decode them first.'
  },
  selectValues: {
    short: 'Allowed option values. Enter one per line or separate with commas.'
  },
  selectMaxSelect: {
    short: 'How many options may be selected. Use 1 for a single-select dropdown.'
  },
  relationMaxSelect: {
    short: 'Maximum number of related record IDs. Use 1 for a single relation.'
  },
  relationCascadeDelete: {
    short: 'When this record is deleted, linked records in the related collection are deleted too.',
    long:
      'Only those the caller may delete: the delete rule of the related collection is checked for each of them.'
  },
  minLength: {
    short: 'Minimum number of characters required in the text value.'
  },
  maxLength: {
    short: 'Maximum number of characters allowed in the text value.'
  },
  pattern: {
    short: 'Regular expression the value must match.',
    long: 'Example: ^[a-z-]+$ - anchors are not added for you, so an unanchored pattern matches anywhere in the value.'
  },
  numberMin: {
    short: 'Smallest allowed number, including decimals.'
  },
  numberMax: {
    short: 'Largest allowed number, including decimals.'
  },
  jsonMaxBytes: {
    short: 'Maximum UTF-8 size of the serialized JSON payload.'
  },
  jsonMaxDepth: {
    short: 'Maximum nesting level of the JSON value.',
    long:
      'Depth 1 means a flat object or array only; depth 2 allows one nested level, e.g. { "meta": { "key": "value" } }.'
  },
  jsonOnlyObject: {
    short: 'Reject values that are JSON arrays. Only objects like { "key": "value" } are allowed.'
  },
  jsonOnlyArray: {
    short: 'Reject values that are JSON objects. Only arrays like ["a", "b"] are allowed.'
  },
  minDate: {
    short: 'Earliest allowed date.'
  },
  maxDate: {
    short: 'Latest allowed date.'
  },
  minTime: {
    short: 'Earliest allowed time on each day.'
  },
  maxTime: {
    short: 'Latest allowed time on each day.'
  },
  minDateTime: {
    short: 'Earliest allowed timestamp.',
    long: 'Use ISO format with timezone, e.g. 2026-01-01T00:00:00Z.'
  },
  maxDateTime: {
    short: 'Latest allowed timestamp.',
    long: 'Use ISO format with timezone, e.g. 2026-12-31T23:59:59Z.'
  },
  defaultBoolean: {
    short: 'Applied on API create when the field is omitted.',
    long: 'No default leaves the field unset in the database.'
  },
  defaultValue: {
    short: 'Applied on API create when the field is omitted.',
    long: 'JSON defaults must be valid JSON text.'
  },
  required: {
    short: 'If enabled, the field must be present on create and cannot be empty.'
  },
  indexEnabled: {
    short: 'Creates a MongoDB index on this field to speed up filtering and sorting.'
  },
  indexDirection: {
    short: 'Ascending or descending order used by the index.'
  },
  indexUnique: {
    short: 'Ensures no two records can share the same value for this field.'
  }
} as const satisfies Record<string, FieldHint>
