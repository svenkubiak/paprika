import type {
  ApiErrorBody,
  AppSettings,
  BootstrapData,
  CollectionDefinition,
  HookDefinition,
  HookTestResult,
  PaginatedRecords,
  PaginatedRequestLogs,
  SuperadminInvite,
  SuperadminSummary,
  TenantDefinition,
  TenantUser,
  TwoFactorSetupResult,
  ValidationError
} from '@/types'

export class ApiError extends Error {
  status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
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
    return data.message || data.error || fallback
  } catch {
    return body || fallback
  }
}

async function request<T>(url: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(url, {
    ...options,
    credentials: 'same-origin',
    headers: {
      Accept: 'application/json',
      ...(options.headers || {})
    }
  })

  if (response.status === 204) {
    return null as T
  }

  const body = await response.text()
  const data = body ? JSON.parse(body) : null

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

  confirmTwoFactor(code: string): Promise<{ twoFactorEnabled: boolean }> {
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

  async importSchema(file: File): Promise<{ collectionsCreated: number; collectionsUpdated: number; hooksRestored: number }> {
    const text = await file.text()
    const response = await fetch('/api/meta/schema/import', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: text
    })
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
    hook?: 'any' | 'fired' | 'blocked'
  ): Promise<PaginatedRequestLogs> {
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
    return request(`/api/admin/request-logs?${params.toString()}`)
  }
}
