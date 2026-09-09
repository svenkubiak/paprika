import { fieldExample } from '@/lib/utils'
import { SYSTEM_TIMESTAMP_EXAMPLE } from '@/lib/system-fields'
import type { FieldDefinition } from '@/types'

export type ApiHttpMethod = 'GET' | 'POST' | 'PATCH' | 'DELETE'

export interface ApiExampleBlock {
  title: string
  description?: string
  code: string
  variant?: 'default' | 'error' | 'success'
}

export interface ApiEndpointDoc {
  id: string
  method: ApiHttpMethod
  path: string
  summary: string
  examples: ApiExampleBlock[]
}

const EXAMPLE_ID = '0198a3f2-7b1c-7d4e-9f0a-1c2d3e4f5a6b'

function recordJson(fields: FieldDefinition[], includeId = true): string {
  const lines: string[] = []
  if (includeId) {
    lines.push(`  "id": "${EXAMPLE_ID}"`)
  }
  lines.push(`  "createdAt": "${SYSTEM_TIMESTAMP_EXAMPLE}"`)
  lines.push(`  "updatedAt": "${SYSTEM_TIMESTAMP_EXAMPLE}"`)
  for (const field of fields) {
    lines.push(`  "${field.name}": ${fieldExample(field.type)}`)
  }
  return `{\n${lines.join(',\n')}\n}`
}

function scalarFields(fields: FieldDefinition[]): FieldDefinition[] {
  return fields.filter((field) => field.type !== 'FILE')
}

function fileFields(fields: FieldDefinition[]): FieldDefinition[] {
  return fields.filter((field) => field.type === 'FILE')
}

function createRequestBody(fields: FieldDefinition[]): string {
  const scalar = scalarFields(fields)
  if (scalar.length === 0) {
    return '{\n  "name": "example"\n}'
  }
  const lines = scalar.map((field) => `  "${field.name}": ${fieldExample(field.type)}`)
  return `{\n${lines.join(',\n')}\n}`
}

function multipartCreateExample(collection: string, fields: FieldDefinition[]): string | null {
  const files = fileFields(fields)
  if (files.length === 0) {
    return null
  }

  const scalar = scalarFields(fields)
  const parts: string[] = [
    `POST /api/collections/${collection}`,
    'Content-Type: multipart/form-data',
    '',
    'Form fields (text):'
  ]
  for (const field of scalar) {
    parts.push(`  ${field.name}=${fieldExample(field.type).replace(/^"|"$/g, '')}`)
  }
  parts.push('', 'File parts:')
  for (const field of files) {
    const maxSelect = field.options?.maxSelect ?? 1
    if (maxSelect > 1) {
      parts.push(`  ${field.name}=@file-a.pdf (repeat part name up to maxSelect=${maxSelect})`)
    } else {
      parts.push(`  ${field.name}=@photo.jpg`)
    }
  }
  parts.push('', 'FILE fields cannot be sent in a JSON body — use multipart/form-data.')
  return parts.join('\n')
}

function patchRequestBody(fields: FieldDefinition[]): string {
  const scalar = scalarFields(fields)
  if (scalar.length === 0) {
    return '{\n  "name": "updated example"\n}'
  }
  const field = scalar[0]
  const value =
    field.type === 'NUMBER' ? '43' : field.type === 'BOOLEAN' ? 'false' : '"updated example"'
  return `{\n  "${field.name}": ${value}\n}`
}

function multipartPatchExample(collection: string, fields: FieldDefinition[]): string | null {
  const files = fileFields(fields)
  if (files.length === 0) {
    return null
  }

  const scalar = scalarFields(fields)
  const parts: string[] = [
    `PATCH /api/collections/${collection}/${EXAMPLE_ID}`,
    'Content-Type: multipart/form-data',
    '',
    'Include only fields to change. New file uploads replace existing files for single-select fields.'
  ]
  if (scalar.length > 0) {
    parts.push('', 'Example text field:')
    parts.push(`  ${scalar[0].name}=updated example`)
  }
  parts.push('', 'Example file field:')
  parts.push(`  ${files[0].name}=@replacement.jpg`)
  return parts.join('\n')
}

function fileFieldReadExample(collection: string, field: FieldDefinition): string {
  const maxSelect = field.options?.maxSelect ?? 1
  const base = `/api/collections/${collection}/${EXAMPLE_ID}/files/${field.name}`
  if (maxSelect > 1) {
    return `${base}/{fileId}`
  }
  return base
}

function fileEndpoints(collection: string, fields: FieldDefinition[]): ApiEndpointDoc[] {
  return fileFields(fields).flatMap((field) => {
    const downloadPath = fileFieldReadExample(collection, field)
    const maxSelect = field.options?.maxSelect ?? 1
    const endpoints: ApiEndpointDoc[] = [
      {
        id: `file-download-${field.name}`,
        method: 'GET',
        path: downloadPath,
        summary: `Download the ${field.name} file. Requires the same auth as record access; viewRule on the parent record applies.`,
        examples: [
          {
            title: 'Request',
            description: 'Authorization: Bearer <accessToken> or admin session cookie',
            code: `GET ${downloadPath}`
          },
          {
            title: 'Response',
            description: '200 OK — binary body with Content-Type from stored mimeType',
            code: '(binary file content)',
            variant: 'success'
          },
          ...recordErrors()
        ]
      },
      {
        id: `file-delete-${field.name}`,
        method: 'DELETE',
        path: downloadPath,
        summary: `Delete the ${field.name} file from the record. updateRule on the parent record applies.`,
        examples: [
          {
            title: 'Request',
            code: `DELETE ${downloadPath}`
          },
          successBlock('Response', '200 OK (empty body)', '(no response body)'),
          ...mutatingErrors(fields)
        ]
      }
    ]

    if (maxSelect > 1) {
      endpoints[0].summary += ' Use the file id from the record when maxSelect > 1.'
    }

    return endpoints
  })
}

function errorBlock(
  title: string,
  description: string,
  code: string
): ApiExampleBlock {
  return { title, description, code, variant: 'error' }
}

function successBlock(
  title: string,
  description: string | undefined,
  code: string
): ApiExampleBlock {
  return { title, description, code, variant: 'success' }
}

const commonErrors = {
  unauthorized: errorBlock(
    '401 Unauthorized',
    'Missing or invalid bearer token',
    '{\n  "error": "Unauthorized"\n}'
  ),
  forbidden: errorBlock(
    '403 Forbidden',
    'Authenticated but not allowed by collection rules',
    '{\n  "error": "Forbidden"\n}'
  ),
  collectionNotFound: errorBlock(
    '404 Not Found',
    'Unknown collection name',
    '{\n  "error": "Collection not found"\n}'
  ),
  recordNotFound: errorBlock(
    '404 Not Found',
    'Record id does not exist',
    '(empty response body)'
  ),
  invalidJson: errorBlock(
    '400 Bad Request',
    'Malformed JSON request body',
    '(empty response body)'
  ),
  noSchemaFields: errorBlock(
    '400 Bad Request',
    'Collection has no schema fields defined',
    '{\n  "error": "Collection has no schema fields defined"\n}'
  ),
  hookRejected: errorBlock(
    '400 Bad Request',
    'Blocking hook rejected the operation',
    '{\n  "error": "title too short"\n}'
  ),
  hookFailed: errorBlock(
    '502 Bad Gateway',
    'Blocking hook failed (unless fail-open is enabled)',
    '{\n  "error": "Hook failed: Post slug generator"\n}'
  )
}

function validationErrorBlock(fields: FieldDefinition[]): ApiExampleBlock {
  const fieldName = fields.find((field) => field.required)?.name ?? fields[0]?.name ?? 'name'
  return errorBlock(
    '400 Bad Request',
    'Schema validation failed',
    `[\n  {\n    "field": "${fieldName}",\n    "message": "Field is required"\n  }\n]`
  )
}

function authErrors(): ApiExampleBlock[] {
  return [commonErrors.unauthorized, commonErrors.forbidden, commonErrors.collectionNotFound]
}

function recordErrors(includeValidation = false, fields: FieldDefinition[] = []): ApiExampleBlock[] {
  const errors = [...authErrors(), commonErrors.recordNotFound]
  if (includeValidation && fields.length > 0) {
    errors.push(validationErrorBlock(fields), commonErrors.invalidJson)
  }
  return errors
}

function mutatingErrors(fields: FieldDefinition[]): ApiExampleBlock[] {
  return [
    ...recordErrors(true, fields),
    commonErrors.hookRejected,
    commonErrors.hookFailed
  ]
}

function deleteErrors(): ApiExampleBlock[] {
  return [...recordErrors(), commonErrors.hookRejected, commonErrors.hookFailed]
}

export function methodColor(method: ApiHttpMethod): 'primary' | 'success' | 'warning' | 'error' | 'neutral' {
  switch (method) {
    case 'GET':
      return 'primary'
    case 'POST':
      return 'success'
    case 'PATCH':
      return 'warning'
    case 'DELETE':
      return 'error'
    default:
      return 'neutral'
  }
}

export function buildCollectionApiDocs(collection: string, fields: FieldDefinition[]): ApiEndpointDoc[] {
  const base = `/api/collections/${collection}`
  const record = recordJson(fields)
  const listItem = recordJson(fields)
  const multipartCreate = multipartCreateExample(collection, fields)
  const multipartPatch = multipartPatchExample(collection, fields)

  const createExamples: ApiExampleBlock[] = [
    {
      title: 'Request',
      description: fileFields(fields).length > 0
        ? 'JSON body for scalar fields only — FILE fields must use multipart (see below)'
        : 'JSON body — id, createdAt, and updatedAt are set by Paprika and must not be sent',
      code: createRequestBody(fields)
    }
  ]
  if (multipartCreate) {
    createExamples.push({
      title: 'Request (multipart)',
      description: 'Upload FILE fields with scalar fields in one request',
      code: multipartCreate
    })
  }
  createExamples.push(
    successBlock('Response', '201 Created (empty body)', '(no response body)'),
    commonErrors.noSchemaFields,
    validationErrorBlock(fields),
    commonErrors.invalidJson,
    commonErrors.hookRejected,
    commonErrors.hookFailed,
    ...authErrors()
  )

  const updateExamples: ApiExampleBlock[] = [
    {
      title: 'Request',
      description: fileFields(fields).length > 0
        ? 'JSON patch for scalar fields — use multipart to replace FILE fields'
        : 'JSON patch body — id, createdAt, and updatedAt are read-only; updatedAt is refreshed on save',
      code: patchRequestBody(fields)
    }
  ]
  if (multipartPatch) {
    updateExamples.push({
      title: 'Request (multipart)',
      description: 'Partial update with optional file replacement',
      code: multipartPatch
    })
  }
  updateExamples.push(
    successBlock('Response', '200 OK (empty body)', '(no response body)'),
    ...mutatingErrors(fields)
  )

  return [
    {
      id: 'list',
      method: 'GET',
      path: base,
      summary: 'List records with pagination and optional rule-based filtering.',
      examples: [
        {
          title: 'Request',
          description: 'Query parameters',
          code: `GET ${base}?offset=0&limit=25`
        },
        {
          title: 'Response',
          description: '200 OK',
          code: `{\n  "items": [\n${listItem
            .split('\n')
            .map((line) => `    ${line}`)
            .join('\n')}\n  ],\n  "total": 1\n}`,
          variant: 'success'
        },
        ...authErrors()
      ]
    },
    {
      id: 'create',
      method: 'POST',
      path: base,
      summary: fileFields(fields).length > 0
        ? 'Create a record. Scalar fields via JSON or multipart; FILE fields require multipart/form-data.'
        : 'Create a record. Body is validated against the collection schema.',
      examples: createExamples
    },
    {
      id: 'read',
      method: 'GET',
      path: `${base}/{id}`,
      summary: 'Fetch a single record by id.',
      examples: [
        {
          title: 'Request',
          code: `GET ${base}/${EXAMPLE_ID}`
        },
        successBlock('Response', '200 OK', record),
        ...recordErrors(),
        commonErrors.hookRejected
      ]
    },
    {
      id: 'update',
      method: 'PATCH',
      path: `${base}/{id}`,
      summary: fileFields(fields).length > 0
        ? 'Partial update. Scalar fields via JSON or multipart; replace FILE fields via multipart.'
        : 'Partial update — only include fields you want to change.',
      examples: updateExamples
    },
    {
      id: 'delete',
      method: 'DELETE',
      path: `${base}/{id}`,
      summary: 'Delete a record permanently.',
      examples: [
        {
          title: 'Request',
          code: `DELETE ${base}/${EXAMPLE_ID}`
        },
        successBlock('Response', '200 OK (empty body)', '(no response body)'),
        ...deleteErrors()
      ]
    },
    ...fileEndpoints(collection, fields)
  ]
}

export const authEndpointDocs: ApiEndpointDoc[] = [
  {
    id: 'register',
    method: 'POST',
    path: '/api/auth/register',
    summary: 'Self-service registration for a tenant. Disabled by default; enable per tenant in the admin UI.',
    examples: [
      {
        title: 'Request',
        description: 'Requires tenant slug and registrationEnabled on the tenant',
        code: `POST /api/auth/register

{
  "tenant": "default",
  "username": "new-user",
  "password": "secret123",
  "email": "new@example.com"
}`
      },
      successBlock('Response', '201 Created', `{
  "id": "…",
  "username": "new-user",
  "email": "new@example.com",
  "role": "user"
}`),
      errorBlock(
        '400 Bad Request',
        'Tenant slug is required',
        '{\n  "error": "Tenant slug is required"\n}'
      ),
      errorBlock(
        '400 Bad Request',
        'Tenant not found',
        '{\n  "error": "Tenant not found"\n}'
      ),
      errorBlock(
        '403 Forbidden',
        'Registration is disabled',
        '{\n  "error": "Registration is disabled"\n}'
      )
    ]
  },
  {
    id: 'login',
    method: 'POST',
    path: '/api/auth/login',
    summary:
      'Authenticate a tenant user. Paprika resolves the tenant from the username across active tenants. Include tenant only when the same username exists in multiple tenants.',
    examples: [
      {
        title: 'Request',
        description: 'Tenant user login (tenant resolved automatically)',
        code: `POST /api/auth/login

{
  "username": "user@example.com",
  "password": "your-password"
}`
      },
      {
        title: 'Request',
        description: 'Explicit tenant slug (optional, for ambiguous usernames)',
        code: `POST /api/auth/login

{
  "tenant": "acme",
  "username": "user@example.com",
  "password": "your-password"
}`
      },
      successBlock(
        'Response',
        '200 OK',
        `{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9…",
  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9…"
}`
      ),
      errorBlock(
        '401 Unauthorized',
        'Invalid username or password',
        '{\n  "error": "Invalid username or password"\n}'
      ),
      errorBlock(
        '403 Forbidden',
        "Email verification is required for login (tenant's Require for login) and the user hasn't verified yet",
        '{\n  "error": "Email address is not verified"\n}'
      ),
      errorBlock(
        '409 Conflict',
        'Ambiguous username across tenants',
        '{\n  "error": "Ambiguous username, specify tenant slug"\n}'
      ),
      errorBlock(
        '400 Bad Request',
        'Unknown tenant slug',
        '{\n  "error": "Tenant not found"\n}'
      )
    ]
  },
  {
    id: 'refresh',
    method: 'POST',
    path: '/api/auth/refresh',
    summary: 'Exchange a valid refresh token for a new access and refresh token pair.',
    examples: [
      {
        title: 'Request',
        code: `POST /api/auth/refresh

{
  "refreshToken": "<refreshToken>"
}`
      },
      successBlock(
        'Response',
        '200 OK',
        `{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9…",
  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9…"
}`
      ),
      errorBlock(
        '401 Unauthorized',
        'Invalid or expired refresh token',
        '{\n  "error": "Invalid refresh token"\n}'
      )
    ]
  },
  {
    id: 'password-forgot',
    method: 'POST',
    path: '/api/auth/password/forgot',
    summary:
      "Start a password reset. Opt-in per tenant (Password reset). Always answers 200 so callers cannot probe accounts; when enabled and the email matches a user, Paprika issues a one-time token and emails the reset link (built from the tenant's reset URL) over the instance SMTP settings.",
    examples: [
      {
        title: 'Request',
        code: `POST /api/auth/password/forgot

{
  "tenant": "default",
  "email": "user@example.com"
}`
      },
      successBlock('Response', '200 OK', '{\n  "success": true\n}')
    ]
  },
  {
    id: 'password-reset',
    method: 'POST',
    path: '/api/auth/password/reset',
    summary:
      'Complete a password reset with the one-time token from the reset email. Sets the new password (minimum 16 characters) and invalidates the token.',
    examples: [
      {
        title: 'Request',
        code: `POST /api/auth/password/reset

{
  "tenant": "default",
  "token": "<token from the reset link>",
  "password": "new-password-at-least-16"
}`
      },
      successBlock('Response', '200 OK', '{\n  "success": true\n}'),
      errorBlock(
        '400 Bad Request',
        'Token invalid, expired, already used, or feature disabled',
        '{\n  "error": "Reset token is invalid or expired"\n}'
      )
    ]
  },
  {
    id: 'verify-request',
    method: 'POST',
    path: '/api/auth/verify/request',
    summary:
      "Start email verification. Opt-in per tenant (Email verification). Always answers 200; when enabled and the email matches a user, Paprika issues a one-time token and emails the verification link (built from the tenant's verification URL) over the instance SMTP settings.",
    examples: [
      {
        title: 'Request',
        code: `POST /api/auth/verify/request

{
  "tenant": "default",
  "email": "user@example.com"
}`
      },
      successBlock('Response', '200 OK', '{\n  "success": true\n}')
    ]
  },
  {
    id: 'verify-confirm',
    method: 'POST',
    path: '/api/auth/verify/confirm',
    summary:
      "Complete email verification with the one-time token. Sets the user's emailVerified flag to true and invalidates the token. Only gates login if the tenant's Require for login switch is on.",
    examples: [
      {
        title: 'Request',
        code: `POST /api/auth/verify/confirm

{
  "tenant": "default",
  "token": "<token from the verification link>"
}`
      },
      successBlock('Response', '200 OK', '{\n  "success": true\n}'),
      errorBlock(
        '400 Bad Request',
        'Token invalid, expired, already used, or feature disabled',
        '{\n  "error": "Verification token is invalid or expired"\n}'
      )
    ]
  }
]

export const realtimeEndpointDocs: ApiEndpointDoc[] = [
  {
    id: 'realtime-connect',
    method: 'GET',
    path: '/api/realtime',
    summary:
      'Open a Server-Sent Events stream. The first event is named connect and carries the clientId needed for subscribe. Listen with addEventListener("connect"), not onmessage.',
    examples: [
      {
        title: 'Request',
        description: 'No Authorization header on the EventSource request',
        code: 'GET /api/realtime'
      },
      successBlock(
        'Event',
        'event: connect',
        `{
  "clientId": "7c2f0e1a-9b4d-4c8e-a1f2-3d5e6f7a8b9c"
}`
      )
    ]
  },
  {
    id: 'realtime-subscribe',
    method: 'POST',
    path: '/api/realtime/subscribe',
    summary:
      'Authenticate the SSE client and register collection or record subscriptions. Until this succeeds, record events are not delivered. Events are also skipped when viewRule denies the subscriber.',
    examples: [
      {
        title: 'Request',
        description: 'Bearer JWT from POST /api/auth/login',
        code: `POST /api/realtime/subscribe
Authorization: Bearer <accessToken>

{
  "clientId": "7c2f0e1a-9b4d-4c8e-a1f2-3d5e6f7a8b9c",
  "subscriptions": ["trips", "trips/${EXAMPLE_ID}"]
}`
      },
      successBlock('Response', '204 No Content', '(no response body)'),
      {
        title: 'SSE event',
        description: 'event: subscribed — confirms auth and subscriptions on the open stream',
        code: `{
  "clientId": "7c2f0e1a-9b4d-4c8e-a1f2-3d5e6f7a8b9c",
  "subscriptions": ["trips"]
}`
      },
      commonErrors.unauthorized,
      errorBlock(
        '404 Not Found',
        'Unknown clientId — connect to GET /api/realtime first and use the clientId from the connect event',
        '{\n  "error": "Unknown clientId"\n}'
      )
    ]
  },
  {
    id: 'realtime-event',
    method: 'GET',
    path: '/api/realtime',
    summary:
      'After subscribe, collection changes arrive as named SSE events. The event name is the collection (e.g. trips). Data is JSON with action and the record.',
    examples: [
      {
        title: 'Create',
        description: 'event: trips — same shape for update and delete (action: update | delete)',
        code: `{
  "action": "create",
  "collection": "trips",
  "record": ${recordJson([])}
}`
      }
    ]
  }
]
