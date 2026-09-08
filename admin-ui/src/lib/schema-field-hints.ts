export const schemaFieldHints = {
  name: 'API field key stored on each record. Use letters, numbers, and underscores.',
  type: 'Controls validation, storage, and how the field appears in the admin record editor.',
  relationCollection:
    'Target collection for the stored relation ID(s). Relations to users always use accounts in the active tenant.',
  fileMaxSize: 'Maximum upload size per file, in bytes. Example: 5242880 = 5 MB.',
  fileMimeTypes:
    'Comma-separated allow list. Examples: image/jpeg, image/png, or image/* for any image type.',
  fileMaxSelect: 'Maximum number of files stored on one record. Use 1 for a single file field.',
  selectValues: 'Allowed option values. Enter one per line or separate with commas.',
  selectMaxSelect: 'How many options may be selected. Use 1 for a single-select dropdown.',
  relationMaxSelect: 'Maximum number of related record IDs. Use 1 for a single relation.',
  relationCascadeDelete:
    'When this record is deleted, linked records in the related collection are deleted too.',
  minLength: 'Minimum number of characters required in the text value.',
  maxLength: 'Maximum number of characters allowed in the text value.',
  pattern: 'Regular expression the value must match. Example: ^[a-z-]+$',
  numberMin: 'Smallest allowed number, including decimals.',
  numberMax: 'Largest allowed number, including decimals.',
  jsonMaxBytes: 'Maximum UTF-8 size of the serialized JSON payload, in bytes.',
  jsonMaxDepth:
    'Maximum nesting level of the JSON value. Depth 1 means a flat object or array only; depth 2 allows one nested level, e.g. { "meta": { "key": "value" } }.',
  jsonOnlyObject: 'Reject values that are JSON arrays. Only objects like { "key": "value" } are allowed.',
  jsonOnlyArray: 'Reject values that are JSON objects. Only arrays like ["a", "b"] are allowed.',
  minDate: 'Earliest allowed date (ISO date, yyyy-MM-dd).',
  maxDate: 'Latest allowed date (ISO date, yyyy-MM-dd).',
  minTime: 'Earliest allowed time on each day.',
  maxTime: 'Latest allowed time on each day.',
  minDateTime: 'Earliest allowed timestamp. Use ISO format with timezone, e.g. 2026-01-01T00:00:00Z.',
  maxDateTime: 'Latest allowed timestamp. Use ISO format with timezone, e.g. 2026-12-31T23:59:59Z.',
  defaultBoolean:
    'Applied on API create when the field is omitted. No default leaves the field unset in the database.',
  defaultValue:
    'Applied on API create when the field is omitted. JSON defaults must be valid JSON text.',
  required: 'If enabled, the field must be present on create and cannot be empty.',
  indexEnabled: 'Creates a MongoDB index on this field to speed up filtering and sorting.',
  indexDirection: 'Ascending or descending order used by the index.',
  indexUnique: 'Ensures no two records can share the same value for this field.'
} as const
