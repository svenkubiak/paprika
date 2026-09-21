<script setup lang="ts">
import { computed } from 'vue'
import type { RequestLogEntry } from '@/types'
import { slideoverUi } from '@/lib/overlay-ui'

const props = defineProps<{
  open: boolean
  entry: RequestLogEntry | null
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  copy: [value: string]
}>()

const entry = computed(() => props.entry)

const description = computed(() => {
  if (!entry.value) return ''
  return `${entry.value.method} ${entry.value.url}`
})

function formatTimestamp(value?: string | null) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleString(undefined, {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
    fractionalSecondDigits: 3
  })
}

function statusColor(code: number) {
  if (code >= 500) return 'error'
  if (code >= 400) return 'warning'
  return 'success'
}

const hookLabel = computed(() => {
  if (!entry.value) return '—'
  if (entry.value.hookBlocked) return 'blocked'
  if (entry.value.hookFired) return 'fired'
  return 'not fired'
})

const hookColor = computed(() => {
  if (entry.value?.hookBlocked) return 'warning'
  if (entry.value?.hookFired) return 'success'
  return 'neutral'
})

const isHookEntry = computed(() => entry.value?.type === 'hook')

const hookInvocations = computed(() => entry.value?.hooks || [])

const hookTotalMs = computed(() => entry.value?.hookTotalMs ?? 0)

/**
 * What the request cost without its hooks. Hooks are remote calls Paprika only waits for, so
 * seeing both numbers separately is the difference between "Paprika is slow" and "your hook is".
 */
const appTimeMs = computed(() => {
  const total = entry.value?.execTimeMs
  if (total == null) return null
  return Math.max(0, total - hookTotalMs.value)
})

const hookShare = computed(() => {
  const total = entry.value?.execTimeMs
  if (!total || total <= 0) return 0
  return Math.min(100, Math.round((hookTotalMs.value / total) * 100))
})

function outcomeColor(outcome: string) {
  switch (outcome) {
    case 'blocked': return 'warning'
    case 'failed': return 'error'
    case 'failedOpen': return 'warning'
    case 'issuedToken': return 'primary'
    default: return 'success'
  }
}
</script>

<template>
  <USlideover
    :open="open"
    title="Request details"
    :description="description"
    :ui="slideoverUi"
    @update:open="emit('update:open', $event)"
  >
    <template #body>
      <div v-if="entry" class="w-full space-y-6 text-base">
        <section class="space-y-3">
          <h3 class="text-sm font-semibold uppercase tracking-wide text-muted">Request</h3>

          <div class="flex flex-wrap items-center gap-2">
            <UBadge color="neutral" variant="soft" size="lg">{{ entry.method }}</UBadge>
            <UBadge :color="statusColor(entry.statusCode)" variant="soft" size="lg">
              {{ entry.statusCode }}
            </UBadge>
            <UBadge :color="hookColor" variant="soft" size="lg">hook: {{ hookLabel }}</UBadge>
            <UBadge v-if="isHookEntry" color="primary" variant="outline" size="lg">
              async hook execution
            </UBadge>
          </div>

          <div class="rounded-lg border border-default bg-muted/30 p-3">
            <p class="mb-1 text-xs font-medium uppercase tracking-wide text-muted">URL</p>
            <button
              class="break-all text-left font-mono text-sm hover:text-primary"
              title="Click to copy"
              @click="emit('copy', entry.url)"
            >
              {{ entry.url }}
            </button>
          </div>

          <dl class="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div>
              <dt class="text-xs font-medium uppercase tracking-wide text-muted">Timestamp</dt>
              <dd class="mt-0.5 font-mono text-sm">{{ formatTimestamp(entry.timestamp) }}</dd>
            </div>
            <div>
              <dt class="text-xs font-medium uppercase tracking-wide text-muted">Execution time</dt>
              <dd class="mt-0.5 font-mono text-sm">
                {{ entry.execTimeMs != null ? `${entry.execTimeMs} ms` : '—' }}
              </dd>
            </div>
            <div v-if="entry.requestId" class="sm:col-span-2">
              <dt class="text-xs font-medium uppercase tracking-wide text-muted">Request ID</dt>
              <dd class="mt-0.5">
                <button
                  class="break-all text-left font-mono text-sm hover:text-primary"
                  title="Click to copy - shared with the async hook entries of this request"
                  @click="emit('copy', entry.requestId!)"
                >
                  {{ entry.requestId }}
                </button>
              </dd>
            </div>
            <div class="sm:col-span-2">
              <dt class="text-xs font-medium uppercase tracking-wide text-muted">Log ID</dt>
              <dd class="mt-0.5">
                <button
                  class="break-all text-left font-mono text-sm hover:text-primary"
                  title="Click to copy"
                  @click="emit('copy', entry.id)"
                >
                  {{ entry.id }}
                </button>
              </dd>
            </div>
          </dl>
        </section>

        <USeparator />

        <section class="space-y-3">
          <h3 class="text-sm font-semibold uppercase tracking-wide text-muted">Timing</h3>

          <dl class="grid grid-cols-3 gap-3">
            <div>
              <dt class="text-xs font-medium uppercase tracking-wide text-muted">Total</dt>
              <dd class="mt-0.5 font-mono text-sm">
                {{ entry.execTimeMs != null ? `${entry.execTimeMs} ms` : '—' }}
              </dd>
            </div>
            <div>
              <dt class="text-xs font-medium uppercase tracking-wide text-muted">Hooks</dt>
              <dd class="mt-0.5 font-mono text-sm">
                {{ entry.hookTotalMs != null ? `${entry.hookTotalMs} ms` : '—' }}
              </dd>
            </div>
            <div>
              <dt class="text-xs font-medium uppercase tracking-wide text-muted">Paprika</dt>
              <dd class="mt-0.5 font-mono text-sm">
                {{ appTimeMs != null ? `${appTimeMs} ms` : '—' }}
              </dd>
            </div>
          </dl>

          <div v-if="hookTotalMs > 0" class="space-y-1">
            <div class="h-2 w-full overflow-hidden rounded-full bg-muted">
              <div class="h-full bg-warning" :style="{ width: `${hookShare}%` }" />
            </div>
            <p class="text-xs text-muted">{{ hookShare }}% of the request was spent waiting for hooks</p>
          </div>
        </section>

        <template v-if="hookInvocations.length">
          <USeparator />

          <section class="space-y-3">
            <h3 class="text-sm font-semibold uppercase tracking-wide text-muted">
              Hook executions ({{ hookInvocations.length }})
            </h3>

            <div
              v-for="(invocation, index) in hookInvocations"
              :key="`${invocation.name}-${index}`"
              class="rounded-lg border border-default bg-muted/20 p-3"
            >
              <div class="flex flex-wrap items-center justify-between gap-2">
                <span class="font-medium">{{ invocation.name }}</span>
                <div class="flex items-center gap-2">
                  <UBadge :color="outcomeColor(invocation.outcome)" variant="soft" size="md">
                    {{ invocation.outcome }}
                  </UBadge>
                  <span class="font-mono text-sm text-muted">{{ invocation.durationMs }} ms</span>
                </div>
              </div>
              <p class="mt-1 font-mono text-xs text-muted">
                {{ invocation.event }}
                <template v-if="invocation.target"> · {{ invocation.target }}</template>
                <template v-if="invocation.status"> · HTTP {{ invocation.status }}</template>
              </p>
            </div>

            <p v-if="entry.hookBlockedBy" class="text-sm text-warning">
              Rejected by hook “{{ entry.hookBlockedBy }}”
            </p>
          </section>
        </template>

        <template v-if="entry.clientIp || entry.userAgent">
          <USeparator />

          <section class="space-y-3">
            <h3 class="text-sm font-semibold uppercase tracking-wide text-muted">Client</h3>

            <dl class="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <div v-if="entry.clientIp">
                <dt class="text-xs font-medium uppercase tracking-wide text-muted">IP address</dt>
                <dd class="mt-0.5 font-mono text-sm">{{ entry.clientIp }}</dd>
              </div>
              <div v-if="entry.userAgent" class="sm:col-span-2">
                <dt class="text-xs font-medium uppercase tracking-wide text-muted">User agent</dt>
                <dd class="mt-0.5 break-all font-mono text-sm">{{ entry.userAgent }}</dd>
              </div>
            </dl>
          </section>
        </template>

        <USeparator />

        <section class="space-y-3">
          <h3 class="text-sm font-semibold uppercase tracking-wide text-muted">Authentication</h3>

          <dl class="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div class="sm:col-span-2">
              <dt class="text-xs font-medium uppercase tracking-wide text-muted">User ID</dt>
              <dd class="mt-0.5">
                <button
                  v-if="entry.userId"
                  class="break-all text-left font-mono text-sm hover:text-primary"
                  title="Click to copy"
                  @click="emit('copy', entry.userId!)"
                >
                  {{ entry.userId }}
                </button>
                <span v-else class="text-sm text-muted">Anonymous request</span>
              </dd>
            </div>
            <div>
              <dt class="text-xs font-medium uppercase tracking-wide text-muted">Role</dt>
              <dd class="mt-1">
                <UBadge
                  v-if="entry.userId"
                  :color="entry.userRole === 'superadmin' ? 'neutral' : 'primary'"
                  :variant="entry.userRole === 'superadmin' ? 'outline' : 'subtle'"
                  size="md"
                >
                  {{ entry.userRole === 'superadmin' ? 'superadmin' : 'user' }}
                </UBadge>
                <span v-else class="text-sm text-muted">—</span>
              </dd>
            </div>
            <div>
              <dt class="text-xs font-medium uppercase tracking-wide text-muted">API key</dt>
              <dd class="mt-1">
                <UBadge v-if="entry.apiKeyId" color="warning" variant="subtle" size="md">
                  {{ entry.apiKeyName || entry.apiKeyId }}
                </UBadge>
                <span v-else class="text-sm text-muted">—</span>
              </dd>
            </div>
            <div class="sm:col-span-2">
              <dt class="text-xs font-medium uppercase tracking-wide text-muted">Collection rules</dt>
              <dd class="mt-1">
                <UBadge v-if="entry.rulesBypassed" color="error" variant="subtle" size="md">
                  bypassed
                </UBadge>
                <UBadge v-else color="neutral" variant="subtle" size="md">applied</UBadge>
              </dd>
            </div>
          </dl>
        </section>

        <template v-if="entry.errorMessage">
          <USeparator />

          <section class="space-y-2">
            <h3 class="text-sm font-semibold uppercase tracking-wide text-muted">Error</h3>
            <pre class="max-h-72 overflow-auto whitespace-pre-wrap break-words rounded-lg border border-error/30 bg-error/5 p-3 font-mono text-sm text-error">{{ entry.errorMessage }}</pre>
          </section>
        </template>
      </div>
    </template>

    <template #footer>
      <div class="flex w-full justify-end">
        <UButton variant="ghost" color="neutral" @click="emit('update:open', false)">Close</UButton>
      </div>
    </template>
  </USlideover>
</template>
