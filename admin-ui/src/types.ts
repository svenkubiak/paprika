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
  /** STRING only: display hint for a textarea in the record editor. */
  multiline?: boolean
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
  // group/peers rules: the membership collection, its user and group fields, and (group only) the
  // field of this collection carrying the group - peers matches on the record id.
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
  includeFileRoutes?: boolean | null
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
  /** Custom fields of the tenant's users schema, returned as they are. */
  [key: string]: unknown
}

export interface ApiKey {
  id: string
  name: string
  userId: string
  keyPrefix: string
  createdAt?: string | null
  lastUsedAt?: string | null
  expiresAt?: string | null
  revokedAt?: string | null
  /** Skips the collection rules. Creation-only. */
  bypassRules?: boolean
  /** Runs no hooks. Creation-only. */
  bypassHooks?: boolean
  /** Allowed source CIDRs, empty means anywhere. Changeable, as it only narrows reach. */
  allowedCidrs?: string[]
}

/** Only the create response carries the plaintext key, and only once. */
export interface CreatedApiKey extends ApiKey {
  key: string
}

export interface Stats {
  collections: number
  records: number
  tenants: number
  /** 5xx responses in the active tenant's request log over 24h; non-zero is shown as a problem. */
  serverErrors24h: number
  uptimeSeconds: number
}

export interface InstanceWarnings {
  /** Tenants relying on password reset or email verification while no SMTP host is configured. */
  mailDependentTenants: string[]
  /** Tenants whose collection definitions are not covered by the unique indexes. */
  degradedIndexTenants: string[]
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
  adminUsername?: string | null
  /** Carries the picture version, so the browser can cache it and still see a new one. */
  adminAvatarUrl?: string | null
  smtpConfigured?: boolean
  hasActiveTenant: boolean
  activeTenant: TenantDefinition | null
  defaultTenantApplied?: boolean
  tenants: TenantDefinition[]
  collections: string[]
  relationCollections?: string[]
  stats: Stats | null
  warnings?: InstanceWarnings | null
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
  // Bean Validation failures from mangoo, keyed by field; sent instead of `error` when the request
  // never reaches the controller.
  errors?: Record<string, string>
}

export interface ValidationError {
  field?: string
  message: string
}

export interface AppSettings {
  requestLogRetentionDays: number
  defaultTenantId?: string | null
  requestLogClientInfo?: boolean
  requestLogClientIp?: 'off' | 'truncated' | 'full'
  requestLogAdminUi?: boolean
  [key: string]: string | boolean | number | null | undefined
}

export interface SuperadminProfile {
  username: string
  email?: string | null
  emailVerified: boolean
  /** A confirmation link is out and has not expired yet. */
  emailVerificationPending: boolean
  loginAlertEnabled: boolean
  twoFactorEnabled: boolean
  smtpConfigured: boolean
  avatarUrl?: string | null
  verificationEmailSent?: boolean
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
  hooksBypassed?: boolean
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
 * No total - counting the whole log on every poll is what would make polling expensive. `limit` is
 * what the server applied, so a truncated delta can be told from a complete one.
 */
export interface RequestLogDelta {
  items: RequestLogEntry[]
  limit: number
}
