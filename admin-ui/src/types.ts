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
  imageWidths?: number[]
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
  // Configuration of the group/peers rules: where the memberships live, which field of that
  // collection points at the user and which at the group, and which field of this collection
  // carries the group (group only - peers matches on the record id).
  groupCollection?: string | null
  groupMemberField?: string | null
  groupField?: string | null
  groupRecordField?: string | null
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
  description?: string | null
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
  forwardHeaders?: string[] | null
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
  emailVerificationRequired?: boolean
  passwordResetUrl?: string | null
  emailVerificationUrl?: string | null
  webhookAllowlist?: string[]
  tokenIssuers?: string[]
}

export interface TenantUser {
  id: string
  username: string
  email?: string | null
  role: string
  createdAt?: string | null
  updatedAt?: string | null
}

/** An API key as the admin UI sees it: never the key itself, only its metadata. */
export interface ApiKey {
  id: string
  name: string
  userId: string
  keyPrefix: string
  createdAt?: string | null
  lastUsedAt?: string | null
  expiresAt?: string | null
  revokedAt?: string | null
  /** Requests with this key skip the collection rules. Set at creation, never changeable. */
  bypassRules?: boolean
}

/** Only the create response carries the plaintext key, and only once. */
export interface CreatedApiKey extends ApiKey {
  key: string
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
  version?: string
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

export interface SchemaImportResult {
  collectionsCreated: number
  collectionsUpdated: number
  hooksRestored: number
  /** Existing collections whose rules the file did not carry and that were therefore left alone. */
  rulesPreserved: number
}

export interface PaginatedRecords {
  items: Record<string, unknown>[]
  total: number
}

export type RulePreset = 'locked' | 'public' | 'auth' | 'owner' | 'group' | 'peers'

export type RuleLevel = '' | '*' | 'auth' | 'owner' | 'group' | 'peers'

export interface ApiErrorBody {
  error?: string
  message?: string
  field?: string
  // Bean Validation failures from mangoo, keyed by the field that failed. Sent instead of
  // `error` when a request never reaches the controller.
  errors?: Record<string, string>
}

export interface ValidationError {
  field?: string
  message: string
}

export interface AppSettings {
  twoFactorEnabled: boolean
  requestLogRetentionDays: number
  defaultTenantId?: string | null
  requestLogClientInfo?: boolean
  requestLogClientIp?: 'off' | 'truncated' | 'full'
  [key: string]: string | boolean | number | null | undefined
}

export interface TwoFactorSetupResult {
  secret: string
  uri: string
}

export interface RequestLogHookInvocation {
  name: string
  event?: string | null
  target?: string | null
  status?: number | null
  durationMs: number
  outcome: 'continued' | 'blocked' | 'issuedToken' | 'failed' | 'failedOpen'
}

export interface RequestLogEntry {
  id: string
  type?: 'request' | 'hook'
  requestId?: string | null
  method: string
  url: string
  statusCode: number
  errorMessage?: string | null
  timestamp: string
  execTimeMs?: number | null
  userId?: string | null
  userRole?: string | null
  apiKeyId?: string | null
  apiKeyName?: string | null
  rulesBypassed?: boolean
  hookFired?: boolean
  hookBlocked?: boolean
  hookBlockedBy?: string | null
  hookCount?: number | null
  hookTotalMs?: number | null
  hooks?: RequestLogHookInvocation[] | null
  userAgent?: string | null
  clientIp?: string | null
}

export interface PaginatedRequestLogs {
  items: RequestLogEntry[]
  total: number
}

/**
 * The answer to a live-mode poll. It carries no total - counting the whole log every few seconds
 * is what would make polling expensive - and `limit` is what the server applied, so the client can
 * tell a complete delta from a truncated one.
 */
export interface RequestLogDelta {
  items: RequestLogEntry[]
  limit: number
}
