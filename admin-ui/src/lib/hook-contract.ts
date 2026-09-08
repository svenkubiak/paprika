import type { HookEvent } from '@/types'

export interface HookContractExample {
  request: unknown
  response: unknown | null
  responseNote?: string
}

const sampleRecord = {
  id: '0198a3f2-7b1c-7d4e-9f0a-1c2d3e4f5a6b',
  title: 'Hello World',
  owner: 'user-abc'
}

const sampleHeaders = {
  Authorization: ['Bearer eyJ…'],
  'Content-Type': ['application/json'],
  'User-Agent': ['MyApp/1.0']
}

function isAuthEvent(event: HookEvent): boolean {
  return (
    event === 'beforeRegister' ||
    event === 'afterRegister' ||
    event === 'beforeLogin' ||
    event === 'afterLogin' ||
    event === 'beforeRefresh' ||
    event === 'afterRefresh'
  )
}

const envelopeBase = (event: HookEvent, collection: string | null, includeSchema: boolean) => ({
  paprika: {
    version: 1,
    event,
    deliveryId: '0198a3f2-…',
    timestamp: '2026-08-12T09:42:00.123Z'
  },
  context: {
    collection,
    recordId:
      event.includes('List') || event === 'beforeRequest' || isAuthEvent(event)
        ? event === 'afterRegister'
          ? sampleRecord.id
          : null
        : sampleRecord.id,
    auth: { id: 'user-abc', role: 'user' },
    http: {
      method: event.startsWith('before') || event.startsWith('after') ? httpMethod(event) : 'GET',
      path: httpPath(event, collection),
      headers: sampleHeaders
    }
  },
  schema: includeSchema
    ? {
        fields: [
          { name: 'title', type: 'STRING', required: true, nullable: false },
          { name: 'owner', type: 'RELATION', required: false, options: { collection: 'users' } }
        ]
      }
    : null
})

function httpPath(event: HookEvent, collection: string | null): string {
  if (event === 'beforeRequest') {
    return collection ? `/api/collections/${collection}` : '/api/auth/login'
  }
  if (event === 'beforeRegister' || event === 'afterRegister') return '/api/auth/register'
  if (event === 'beforeLogin' || event === 'afterLogin') return '/api/auth/login'
  if (event === 'beforeRefresh' || event === 'afterRefresh') return '/api/auth/refresh'
  return `/api/collections/${collection || 'posts'}`
}

function httpMethod(event: HookEvent): string {
  if (event === 'beforeRequest') return 'POST'
  if (isAuthEvent(event)) return 'POST'
  if (event.includes('Create')) return 'POST'
  if (event.includes('Update')) return 'PATCH'
  if (event.includes('Delete')) return 'DELETE'
  if (event === 'beforeView') return 'GET'
  return 'GET'
}

function dataForEvent(event: HookEvent) {
  switch (event) {
    case 'beforeRequest':
      return { body: { username: 'demo', password: '…' }, record: null }
    case 'beforeCreate':
      return { body: { title: 'Hello World', content: 'Lorem ipsum' }, record: null }
    case 'afterCreate':
      return {
        body: { title: 'Hello World', content: 'Lorem ipsum' },
        record: sampleRecord
      }
    case 'beforeUpdate':
      return { body: { title: 'New Title' }, record: sampleRecord }
    case 'afterUpdate':
      return { body: { title: 'New Title' }, record: { ...sampleRecord, title: 'New Title' } }
    case 'beforeView':
      return { body: null, record: sampleRecord }
    case 'beforeDelete':
    case 'afterDelete':
      return { body: null, record: sampleRecord }
    case 'beforeList':
      return { body: null, record: null }
    case 'beforeRegister':
      return { body: { username: 'jane', email: 'jane@example.com' }, record: null }
    case 'afterRegister':
      return {
        body: { username: 'jane', email: 'jane@example.com' },
        record: { id: sampleRecord.id, username: 'jane', email: 'jane@example.com', role: 'user' }
      }
    case 'beforeLogin':
      return { body: { username: 'jane', tenant: 'acme' }, record: null }
    case 'afterLogin':
      return { body: { username: 'jane', userId: 'user-abc' }, record: null }
    case 'beforeRefresh':
      return { body: { userId: 'user-abc' }, record: null }
    case 'afterRefresh':
      return { body: { userId: 'user-abc' }, record: null }
    default:
      return { body: null, record: null }
  }
}

export function hookContractExample(
  event: HookEvent,
  collection: string,
  includeSchema: boolean
): HookContractExample {
  const request = {
    ...envelopeBase(event, event === 'beforeRequest' ? null : collection, includeSchema),
    data: dataForEvent(event)
  }

  if (event.startsWith('after')) {
    return {
      request,
      response: null,
      responseNote: 'Non-blocking hooks are fire-and-forget. Paprika ignores the response body.'
    }
  }

  if (event === 'beforeRequest') {
    return {
      request,
      response: {
        continue: false,
        error: { status: 403, message: 'Blocked by policy' }
      },
      responseNote:
        'When continue is false, Paprika returns the error object (status + body) directly to the client.'
    }
  }

  if (event === 'beforeView' || event === 'beforeDelete') {
    return {
      request,
      response: {
        continue: false,
        error: { status: 404, message: 'Not allowed' }
      },
      responseNote: 'Rejecting aborts the operation. The error body is returned to the client as-is.'
    }
  }

  if (event === 'beforeList') {
    return {
      request,
      response: { continue: true },
      responseNote: 'List hooks receive metadata only in phase 1. Reject with continue: false to block.'
    }
  }

  if (event === 'beforeCreate') {
    return {
      request,
      response: {
        continue: true,
        data: {
          body: {
            title: 'Hello World',
            content: 'raw text',
            slug: 'hello-world',
            owner: 'user-abc'
          }
        }
      },
      responseNote: 'Paprika validates and persists the returned body.'
    }
  }

  if (event === 'beforeRegister') {
    return {
      request,
      response: {
        continue: true,
        data: { body: { username: 'jane', email: 'jane@example.com', plan: 'free' } }
      },
      responseNote:
        'Return continue:false to reject the registration. data.body may enrich the stored user; credential fields are ignored.'
    }
  }

  if (event === 'beforeLogin' || event === 'beforeRefresh') {
    return {
      request,
      response: { continue: false, error: { status: 401, message: 'Blocked by policy' } },
      responseNote:
        'Reject with continue:false to block authentication. The plaintext password is never included in the envelope.'
    }
  }

  return {
    request,
    response: {
      continue: true,
      data: {
        body: {
          title: 'New Title',
          slug: 'new-title'
        }
      }
    },
    responseNote: 'For updates, return the patch delta in data.body.'
  }
}

export const hookRequestHeaders = [
  'Content-Type: application/json',
  'X-Paprika-Event',
  'X-Paprika-Collection',
  'X-Paprika-Delivery-Id',
  'X-Paprika-Signature: sha256=… (HMAC-SHA256 over raw request body, required)'
]
