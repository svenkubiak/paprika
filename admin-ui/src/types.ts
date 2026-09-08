export type FieldType =
  | 'STRING'
  | 'NUMBER'
  | 'BOOLEAN'
  | 'EMAIL'
  | 'URL'
  | 'DATE'
  | 'TIME'
  | 'DATETIME'
  | 'RELATION'
  | 'FILE'
  | 'JSON'
  | 'SELECT'

export interface FieldDefinition {
  name: string
  type: FieldType
  required: boolean
  nullable?: boolean
  options?: FieldOptions | null
  default?: unknown
}

export interface FieldOptions {
  collection?: string
  maxSize?: number
  mimeTypes?: string[]
  maxSelect?: number
  values?: string[]
  cascadeDelete?: boolean
  minLength?: number
  maxLength?: number
  pattern?: string
  numberMin?: number
  numberMax?: number
  maxBytes?: number
  maxDepth?: number
  onlyObject?: boolean
  onlyArray?: boolean
  minDate?: string
  maxDate?: string
  minTime?: string
  maxTime?: string
  minDateTime?: string
  maxDateTime?: string
}

export type IndexDirection = 'ASC' | 'DESC'

export interface IndexField {
  field: string
  direction: IndexDirection
}

export interface IndexDefinition {
  name: string
  unique: boolean
  fields: IndexField[]
}

export interface CollectionRules {
  listRule: string | null
  viewRule: string | null
  createRule: string | null
  updateRule: string | null
  deleteRule: string | null
  ownerField?: string | null
}

export type HookEvent =
  | 'beforeRequest'
  | 'beforeList'
  | 'beforeView'
  | 'beforeCreate'
  | 'afterCreate'
  | 'beforeUpdate'
  | 'afterUpdate'
  | 'beforeDelete'
  | 'afterDelete'
  | 'beforeRegister'
  | 'afterRegister'
  | 'beforeLogin'
  | 'afterLogin'
  | 'beforeRefresh'
  | 'afterRefresh'

export interface HookDefinition {
  id: string
  name: string
  collection: string
  event: HookEvent
  url: string
  method?: string | null
  timeoutMs?: number | null
  secret: string
  headers?: Record<string, string> | null
  enabled?: boolean | null
  priority?: number | null
  includeSchema?: boolean | null
  failOpen?: boolean | null
  applyToAllCollections?: boolean | null
  targetCollections?: string[] | null
}

export interface HookTestResult {
  deliveryId: string | null
  requestPayload: string | null
  signature: string | null
  statusCode: number
  responseBody: string | null
  latencyMs: number
  error: string | null
}

export interface CollectionDefinition {
  id: string
  name: string
  fields: FieldDefinition[]
  indexes: IndexDefinition[]
  rules: CollectionRules | null
  system?: boolean
}

export interface TenantDefinition {
  id: string
  name: string
  slug: string
  databaseName: string
  status: string
  createdAt?: string
  registrationEnabled?: boolean
  passwordResetEnabled?: boolean
  emailVerificationEnabled?: boolean
  passwordResetUrl?: string | null
  emailVerificationUrl?: string | null
}

export interface TenantUser {
  id: string
  username: string
  email?: string | null
  role: string
  createdAt?: string | null
  updatedAt?: string | null
}

export interface Stats {
  connected: boolean
  healthy: boolean
  collections: number
  records: number
  tenants: number
  uptimeSeconds: number
}

export interface SuperadminSummary {
  id: string
  username: string
  email?: string | null
  pending: boolean
  twoFactorEnabled: boolean
}

export interface SuperadminInvite {
  username: string
  token: string
  setupPath: string
}

export interface BootstrapData {
  authenticated: boolean
  isSuperAdmin: boolean
  adminId?: string | null
  smtpConfigured?: boolean
  hasActiveTenant: boolean
  activeTenant: TenantDefinition | null
  defaultTenantApplied?: boolean
  tenants: TenantDefinition[]
  collections: string[]
  relationCollections?: string[]
  stats: Stats | null
}

export interface PaginatedRecords {
  items: Record<string, unknown>[]
  total: number
}

export type RulePreset = 'locked' | 'public' | 'auth' | 'owner'

export type RuleLevel = '' | '*' | 'auth' | 'owner'

export interface ApiErrorBody {
  error?: string
  message?: string
  field?: string
}

export interface ValidationError {
  field?: string
  message: string
}

export interface AppSettings {
  twoFactorEnabled: boolean
  requestLogRetentionDays: number
  defaultTenantId?: string | null
  [key: string]: string | boolean | number | null | undefined
}

export interface TwoFactorSetupResult {
  secret: string
  uri: string
}

export interface RequestLogEntry {
  id: string
  method: string
  url: string
  statusCode: number
  errorMessage?: string | null
  timestamp: string
}

export interface PaginatedRequestLogs {
  items: RequestLogEntry[]
  total: number
}
