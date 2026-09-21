import type {
  ApiErrorBody,
  ApiKey,
  CreatedApiKey,
  AppSettings,
  BootstrapData,
  CollectionDefinition,
  HookDefinition,
  HookTestResult,
  PaginatedRecords,
  PaginatedRequestLogs,
  RequestLogDelta,
  SchemaImportResult,
  SuperadminInvite,
  SuperadminSummary,
  TenantDefinition,
  TenantUser,
  TwoFactorSetupResult,
  ValidationError
} from '@/types'

export class ApiError extends Error {
  status: number
  /** Set when the request failed because the admin session is gone, not because of its payload. */
  sessionExpired: boolean
  /**
   * Set when the request never reached an application answer - the connection failed, timed out,
   * or a proxy answered for a server that is not there. Telling this apart from a 401 matters:
   * both used to end up in the same catch, so a restart signed everybody out of a session that
   * was still perfectly valid.
   */
  networkError: boolean

  constructor(message: string, status: number, sessionExpired = false, networkError = false) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.sessionExpired = sessionExpired
    this.networkError = networkError
  }
}

export function isNetworkError(error: unknown): boolean {
  return error instanceof ApiError && error.networkError
}

/** Without this a server that accepts the connection but does not answer yet (it is still
 *  starting) leaves the router guard awaiting forever, i.e. the app hangs on its placeholder. */
const REQUEST_TIMEOUT_MS = 15_000

/** What a reverse proxy answers while the application behind it is restarting. */
const UPSTREAM_STATUS = new Set([502, 503, 504])

const LOGIN_PATH = '/login'

/**
 * Endpoints where a 401 is the normal answer to a wrong credential rather than a session that ran
 * out. These are the ones used to get a session in the first place, so bouncing them to the login
 * page would loop and swallow the "wrong password" the user needs to see.
 */
const CREDENTIAL_ENDPOINTS = new Set([
  '/api/admin/login',
  '/api/admin/login/2fa',
  '/api/admin/setup',
  '/api/admin/token',
  '/api/admin/token/2fa'
])

type SessionExpiredHandler = () => void

let sessionExpiredHandler: SessionExpiredHandler | undefined
let sessionExpiredNotified = false

/**
 * Installed by the router. Keeping the navigation out of this module is what stops an expired
 * session from being answered twice - once by a full page load started here and once by the
 * router guard that catches the error below. Those two raced each other, and the loser was an
 * aborted navigation with an empty <div id="app">.
 */
export function onSessionExpired(handler: SessionExpiredHandler): void {
  sessionExpiredHandler = handler
}

/** Lets the login page report that a session exists again. */
export function resetSessionExpired(): void {
  sessionExpiredNotified = false
}

function sessionExpired(): never {
  // A page can fire several requests at once, and all of them fail the same way - only the first
  // one gets to trigger the handler.
  if (!sessionExpiredNotified) {
    sessionExpiredNotified = true
    sessionExpiredHandler?.()
  }

  throw new ApiError('Your session has expired', 401, true)
}

/**
 * An expired admin session reaches the client in two shapes: the meta and admin API answer with a
 * 401 from AdminAuthFilter, while a route bound withAuthentication() redirects to the login page
 * and fetch follows that transparently, leaving a 200 that carries the admin UI shell. Both mean
 * the same thing and neither is something a calling page can do anything useful with.
 */
function guardSession(response: Response, url: string): void {
  if (CREDENTIAL_ENDPOINTS.has(url)) {
    return
  }

  if (response.status === 401 || (response.redirected && new URL(response.url).pathname === LOGIN_PATH)) {
    sessionExpired()
  }
}

function parseErrorMessage(body: string, fallback: string): string {
  try {
    const data = JSON.parse(body) as ApiErrorBody | ValidationError[]
    if (Array.isArray(data)) {
      return data
        .map((entry) => (entry.field ? `${entry.field}: ${entry.message}` : entry.message))
        .join(', ')
    }
    if (data.errors) {
      // mangoo builds this from a HashMap, so the order is arbitrary - sort it to keep the
      // message stable when more than one field failed.
      const entries = Object.entries(data.errors).sort(([a], [b]) => a.localeCompare(b))
      // The key is whatever the violated constraint sits on, which for a body-level @NotNull is
      // the Java parameter name (`registerDto`). Naming the key only helps when there is more
      // than one message to tell apart, so a single violation is shown on its own - that also
      // keeps internal parameter names out of the UI.
      const fields =
        entries.length > 1
          ? entries.map(([field, message]) => (field ? `${field}: ${message}` : message))
          : entries.map(([, message]) => message)
      const joined = fields.filter(Boolean).join(', ')
      if (joined) {
        return joined
      }
    }
    return data.message || data.error || fallback
  } catch {
    return body || fallback
  }
}

async function request<T>(url: string, options: RequestInit = {}): Promise<T> {
  let response: Response

  try {
    response = await fetch(url, {
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      ...options,
      credentials: 'same-origin',
      headers: {
        Accept: 'application/json',
        ...(options.headers || {})
      }
    })
  } catch (error) {
    // fetch only rejects when there was no HTTP answer at all - a dead connection, a refused
    // one, or the timeout above.
    throw new ApiError('The server could not be reached', 0, false, true)
  }

  guardSession(response, url)

  if (UPSTREAM_STATUS.has(response.status)) {
    throw new ApiError('The server is currently unavailable', response.status, false, true)
  }

  if (response.status === 204) {
    return null as T
  }

  const body = await response.text()

  let data: unknown = null
  try {
    data = body ? JSON.parse(body) : null
  } catch {
    // Not JSON, so this did not come from the application: an error page from whatever sits in
    // front of it. Reporting the HTML verbatim helps nobody.
    if (!response.ok) {
      throw new ApiError('The server returned an unexpected response', response.status, false, true)
    }
    throw new ApiError('The server returned an unexpected response', response.status)
  }

  if (!response.ok) {
    throw new ApiError(parseErrorMessage(body, 'Request failed'), response.status)
  }

  return data as T
}

export const api = {
  bootstrap(): Promise<BootstrapData> {
    return request('/admin/bootstrap')
  },

  login(
    username: string,
    password: string
  ): Promise<{ success?: boolean; requiresTwoFactor?: boolean }> {
    return request('/api/admin/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    })
  },

  loginTwoFactor(code: string): Promise<{ success: boolean }> {
    return request('/api/admin/login/2fa', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code })
    })
  },

  completeSuperadminSetup(token: string, username: string, password: string): Promise<{ success: boolean }> {
    return request('/api/admin/setup', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token, username, password })
    })
  },

  logout(): Promise<void> {
    return fetch('/logout', {
      method: 'POST',
      credentials: 'same-origin',
      redirect: 'manual'
    }).then((response) => {
      if (response.type === 'opaqueredirect' || response.status === 302 || response.ok) {
        return
      }
      throw new ApiError('Failed to log out', response.status)
    })
  },

  switchTenant(tenantId: string): Promise<void> {
    const body = new URLSearchParams({ tenantId, redirect: '/' })
    return fetch('/admin/switch-tenant', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body.toString()
    }).then((response) => {
      guardSession(response, '/admin/switch-tenant')
      if (!response.ok && response.status !== 302) {
        throw new ApiError('Failed to switch tenant', response.status)
      }
    })
  },

  listTenants(): Promise<TenantDefinition[]> {
    return request('/api/meta/tenants')
  },

  createTenant(name: string, slug: string): Promise<TenantDefinition> {
    return request('/api/meta/tenants', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, slug })
    })
  },

  deleteTenant(id: string): Promise<void> {
    return request(`/api/meta/tenants/${encodeURIComponent(id)}`, { method: 'DELETE' })
  },

  updateTenant(
    id: string,
    payload: {
      name?: string
      slug?: string
      registrationEnabled?: boolean
      passwordResetEnabled?: boolean
      emailVerificationEnabled?: boolean
      emailVerificationRequired?: boolean
      passwordResetUrl?: string | null
      emailVerificationUrl?: string | null
      webhookAllowlist?: string[]
      tokenIssuers?: string[]
    }
  ): Promise<TenantDefinition> {
    return request(`/api/meta/tenants/${encodeURIComponent(id)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
  },

  listTenantUsers(tenantId: string): Promise<TenantUser[]> {
    return request(`/api/meta/tenants/${encodeURIComponent(tenantId)}/users`)
  },

  createTenantUser(
    tenantId: string,
    username: string,
    password: string,
    email?: string | null
  ): Promise<TenantUser> {
    return request(`/api/meta/tenants/${encodeURIComponent(tenantId)}/users`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password, email: email || undefined })
    })
  },

  updateTenantUser(
    tenantId: string,
    userId: string,
    payload: { username?: string; password?: string; email?: string | null }
  ): Promise<TenantUser> {
    return request(
      `/api/meta/tenants/${encodeURIComponent(tenantId)}/users/${encodeURIComponent(userId)}`,
      {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      }
    )
  },

  deleteTenantUser(tenantId: string, userId: string): Promise<void> {
    return request(
      `/api/meta/tenants/${encodeURIComponent(tenantId)}/users/${encodeURIComponent(userId)}`,
      { method: 'DELETE' }
    )
  },

  listApiKeys(tenantId: string): Promise<ApiKey[]> {
    return request(`/api/meta/tenants/${encodeURIComponent(tenantId)}/api-keys`)
  },

  /** The response of this call is the only place the plaintext key is ever available. */
  createApiKey(
    tenantId: string,
    payload: {
      name: string
      userId: string
      expiresAt?: string | null
      /** Only settable here: the flag cannot be changed after creation. */
      bypassRules?: boolean
    }
  ): Promise<CreatedApiKey> {
    return request(`/api/meta/tenants/${encodeURIComponent(tenantId)}/api-keys`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: payload.name,
        userId: payload.userId,
        expiresAt: payload.expiresAt || undefined,
        bypassRules: payload.bypassRules === true
      })
    })
  },

  /** Stops the key from authenticating, but keeps its record visible in the list. */
  revokeApiKey(tenantId: string, keyId: string): Promise<void> {
    return request(
      `/api/meta/tenants/${encodeURIComponent(tenantId)}/api-keys/${encodeURIComponent(keyId)}/revoke`,
      { method: 'POST' }
    )
  },

  /** Removes the record as well - the key is gone from the list afterwards. */
  deleteApiKey(tenantId: string, keyId: string): Promise<void> {
    return request(
      `/api/meta/tenants/${encodeURIComponent(tenantId)}/api-keys/${encodeURIComponent(keyId)}`,
      { method: 'DELETE' }
    )
  },

  getCollectionDefinition(name: string): Promise<CollectionDefinition> {
    return request(`/api/meta/collections/${encodeURIComponent(name)}`)
  },

  createCollectionDefinition(name: string): Promise<void> {
    return request(`/api/meta/collections/${encodeURIComponent(name)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, fields: [] })
    })
  },

  updateCollectionDefinition(
    name: string,
    id: string,
    definition: CollectionDefinition
  ): Promise<void> {
    return request(`/api/meta/collections/${encodeURIComponent(name)}/${encodeURIComponent(id)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(definition)
    })
  },

  deleteCollectionDefinition(name: string, id: string): Promise<void> {
    return request(`/api/meta/collections/${encodeURIComponent(name)}/${encodeURIComponent(id)}`, {
      method: 'DELETE'
    })
  },

  listHooks(collection: string): Promise<HookDefinition[]> {
    return request(`/api/meta/collections/${encodeURIComponent(collection)}/hooks`)
  },

  createHook(
    collection: string,
    hook: Omit<HookDefinition, 'id'>
  ): Promise<HookDefinition> {
    return request(`/api/meta/collections/${encodeURIComponent(collection)}/hooks`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(hook)
    })
  },

  updateHook(
    collection: string,
    id: string,
    hook: Partial<Omit<HookDefinition, 'id' | 'collection'>>
  ): Promise<HookDefinition> {
    return request(
      `/api/meta/collections/${encodeURIComponent(collection)}/hooks/${encodeURIComponent(id)}`,
      {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(hook)
      }
    )
  },

  deleteHook(collection: string, id: string): Promise<void> {
    return request(
      `/api/meta/collections/${encodeURIComponent(collection)}/hooks/${encodeURIComponent(id)}`,
      { method: 'DELETE' }
    )
  },

  testHook(
    collection: string,
    hook: Omit<HookDefinition, 'id' | 'collection'> & { id?: string }
  ): Promise<HookTestResult> {
    return request(`/api/meta/collections/${encodeURIComponent(collection)}/hooks/test`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...hook, collection })
    })
  },

  listGlobalHooks(): Promise<HookDefinition[]> {
    return request('/api/meta/global-hooks')
  },

  createGlobalHook(
    hook: Omit<HookDefinition, 'id' | 'collection' | 'event'>
  ): Promise<HookDefinition> {
    return request('/api/meta/global-hooks', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...hook, event: 'beforeRequest' })
    })
  },

  updateGlobalHook(
    id: string,
    hook: Partial<Omit<HookDefinition, 'id' | 'collection' | 'event'>>
  ): Promise<HookDefinition> {
    return request(`/api/meta/global-hooks/${encodeURIComponent(id)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(hook)
    })
  },

  deleteGlobalHook(id: string): Promise<void> {
    return request(`/api/meta/global-hooks/${encodeURIComponent(id)}`, { method: 'DELETE' })
  },

  testGlobalHook(
    hook: Omit<HookDefinition, 'id' | 'collection'> & { id?: string }
  ): Promise<HookTestResult> {
    return request('/api/meta/global-hooks/test', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...hook, collection: '*', event: 'beforeRequest' })
    })
  },

  listRecords(
    collection: string,
    offset: number,
    limit: number
  ): Promise<PaginatedRecords> {
    return request(
      `/api/collections/${encodeURIComponent(collection)}?offset=${offset}&limit=${limit}`
    )
  },

  getRecord(collection: string, id: string): Promise<Record<string, unknown>> {
    return request(`/api/collections/${encodeURIComponent(collection)}/${encodeURIComponent(id)}`)
  },

  createRecord(collection: string, payload: Record<string, unknown>): Promise<void> {
    const body = { ...payload }
    delete body.id
    return request(`/api/collections/${encodeURIComponent(collection)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    })
  },

  saveRecordMultipart(
    collection: string,
    values: Record<string, unknown>,
    files: Record<string, File[]>,
    id?: string
  ): Promise<void> {
    const formData = new FormData()
    formData.append('__paprika_data', JSON.stringify(values))
    for (const [fieldName, selected] of Object.entries(files)) {
      for (const file of selected) {
        formData.append(fieldName, file, file.name)
      }
    }

    const url = id
      ? `/api/collections/${encodeURIComponent(collection)}/${encodeURIComponent(id)}`
      : `/api/collections/${encodeURIComponent(collection)}`

    return fetch(url, {
      method: id ? 'PATCH' : 'POST',
      credentials: 'same-origin',
      body: formData
    }).then((response) => {
      guardSession(response, url)
      if (!response.ok && response.status !== 204) {
        throw new ApiError('Request failed', response.status)
      }
    })
  },

  updateRecord(
    collection: string,
    id: string,
    payload: Record<string, unknown>
  ): Promise<void> {
    const body = { ...payload }
    delete body.id
    return request(`/api/collections/${encodeURIComponent(collection)}/${encodeURIComponent(id)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    })
  },

  deleteRecord(collection: string, id: string): Promise<void> {
    return request(`/api/collections/${encodeURIComponent(collection)}/${encodeURIComponent(id)}`, {
      method: 'DELETE'
    })
  },

  listSuperadmins(): Promise<SuperadminSummary[]> {
    return request('/api/admin/superadmins')
  },

  inviteSuperadmin(username: string, email?: string | null): Promise<SuperadminInvite> {
    return request('/api/admin/superadmins', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, email: email || undefined })
    })
  },

  emailSuperadminInvite(token: string, email: string, username: string): Promise<{ success: boolean }> {
    return request('/api/admin/superadmins/invite/email', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token, email, username })
    })
  },

  deleteSuperadmin(id: string): Promise<void> {
    return request(`/api/admin/superadmins/${encodeURIComponent(id)}`, { method: 'DELETE' })
  },

  getSettings(): Promise<AppSettings> {
    return request('/api/admin/settings')
  },

  updateSettings(settings: {
    requestLogRetentionDays?: number
    defaultTenantId?: string | null
    requestLogClientInfo?: boolean
    requestLogClientIp?: 'off' | 'truncated' | 'full'
  }): Promise<AppSettings> {
    return request('/api/admin/settings', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(settings)
    })
  },

  changeSuperadminPassword(
    currentPassword: string,
    newPassword: string
  ): Promise<{ success: boolean }> {
    return request('/api/admin/settings/password', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ currentPassword, newPassword })
    })
  },

  setupTwoFactor(password: string): Promise<TwoFactorSetupResult> {
    return request('/api/admin/settings/2fa/setup', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password })
    })
  },

  confirmTwoFactor(code: string): Promise<{ twoFactorEnabled: boolean; fallbackCode: string }> {
    return request('/api/admin/settings/2fa/confirm', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code })
    })
  },

  disableTwoFactor(password: string, code: string): Promise<{ twoFactorEnabled: boolean }> {
    return request('/api/admin/settings/2fa/disable', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password, code })
    })
  },

  exportSchema(): void {
    window.location.href = '/api/meta/schema/export'
  },

  async importSchema(file: File): Promise<SchemaImportResult> {
    const text = await file.text()
    const response = await fetch('/api/meta/schema/import', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: text
    })
    guardSession(response, '/api/meta/schema/import')
    const body = await response.text()
    if (!response.ok) {
      throw new ApiError(parseErrorMessage(body, 'Schema import failed'), response.status)
    }
    return JSON.parse(body)
  },

  exportBackup(): void {
    window.location.href = '/api/admin/backup/export'
  },

  async importBackup(file: File): Promise<{ tenants: number; collections: number; documents: number; files: number }> {
    const formData = new FormData()
    formData.append('file', file)
    const response = await fetch('/api/admin/backup/import', {
      method: 'POST',
      credentials: 'same-origin',
      body: formData
    })
    guardSession(response, '/api/admin/backup/import')
    const body = await response.text()
    if (!response.ok) {
      throw new ApiError(parseErrorMessage(body, 'Import failed'), response.status)
    }
    return JSON.parse(body)
  },

  listRequestLogs(
    offset: number,
    limit: number,
    search: string,
    status: 'all' | 'success' | 'error',
    hook?: 'any' | 'fired' | 'blocked',
    type?: 'all' | 'request' | 'hook'
  ): Promise<PaginatedRequestLogs> {
    return request(
      `/api/admin/request-logs?${requestLogParams(offset, limit, search, status, hook, type).toString()}`
    )
  },

  /**
   * One live-mode tick: the same filtered read, bounded to what was logged at or after `since`.
   * The bound is inclusive on the server, so the caller has to drop entries it already shows.
   */
  listRequestLogsSince(
    since: string,
    limit: number,
    search: string,
    status: 'all' | 'success' | 'error',
    hook?: 'any' | 'fired' | 'blocked',
    type?: 'all' | 'request' | 'hook'
  ): Promise<RequestLogDelta> {
    const params = requestLogParams(0, limit, search, status, hook, type)
    params.set('since', since)
    return request(`/api/admin/request-logs?${params.toString()}`)
  }
}

function requestLogParams(
  offset: number,
  limit: number,
  search: string,
  status: 'all' | 'success' | 'error',
  hook?: 'any' | 'fired' | 'blocked',
  type?: 'all' | 'request' | 'hook'
): URLSearchParams {
  const params = new URLSearchParams({
    offset: String(offset),
    limit: String(limit),
    status
  })
  if (search.trim()) {
    params.set('search', search.trim())
  }
  if (hook && hook !== 'any') {
    params.set('hook', hook)
  }
  if (type && type !== 'all') {
    params.set('type', type)
  }
  return params
}
