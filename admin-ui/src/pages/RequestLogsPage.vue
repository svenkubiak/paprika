<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, ApiError } from '@/lib/api'
import { useAppToast } from '@/composables/useAppToast'
import { useBootstrap } from '@/composables/useBootstrap'
import RequestLogDetailSheet from '@/components/RequestLogDetailSheet.vue'
import type { RequestLogEntry } from '@/types'

const toast = useAppToast()
const { load, bootstrap } = useBootstrap()

const logs = ref<RequestLogEntry[]>([])
const total = ref(0)
const loading = ref(false)
const search = ref('')
const statusFilter = ref<'all' | 'success' | 'error'>('all')
const hookFilter = ref<'any' | 'continued' | 'blocked'>('any')
const typeFilter = ref<'all' | 'request' | 'hook'>('all')
const page = ref(1)
const pageSize = ref(50)
const detailOpen = ref(false)
const selectedEntry = ref<RequestLogEntry | null>(null)
const live = ref(false)
const newEntries = ref(0)

/**
 * Slow enough that a tab left open costs nothing worth mentioning, fast enough that "live" is not
 * a lie. Each tick is one indexed range read for what is newer than the top row, not a new page.
 */
const LIVE_INTERVAL_MS = 3000

let liveTimer: ReturnType<typeof setInterval> | undefined

const hasActiveTenant = computed(() => !!bootstrap.value?.hasActiveTenant)

const pageSizeOptions = [
  { label: '25', value: 25 },
  { label: '50', value: 50 },
  { label: '100', value: 100 }
]

/**
 * The header is two rows deep because half of the columns describe the incoming call and the
 * other half the outgoing hook calls it triggered - without that grouping "Hooks" and "Error"
 * read as properties of the request, which is exactly what they are not.
 */
const columnGroups = [
  { key: 'request', label: 'Request', span: 5, title: 'The incoming API call' },
  { key: 'timing', label: 'Timing', span: 3, title: 'Where the time of this request was spent' },
  { key: 'hooks', label: 'Hooks', span: 2, title: 'The hook endpoints Paprika called while handling this request' }
]

const columns = [
  { key: 'timestamp', header: 'Timestamp', title: 'When the entry was written' },
  { key: 'method', header: 'Method', title: 'HTTP method - HOOK marks an entry written by an async hook' },
  { key: 'url', header: 'URL', title: 'Request path, without the query string' },
  { key: 'statusCode', header: 'Status', title: 'HTTP status the client received' },
  { key: 'userId', header: 'User', title: 'Identity the request was authenticated with' },
  { key: 'appTimeMs', header: 'Paprika', title: 'Time spent in Paprika itself, waiting for hooks excluded' },
  { key: 'hookTotalMs', header: 'Hooks', title: 'Time spent waiting for hook endpoints' },
  { key: 'execTimeMs', header: 'Total', title: 'Paprika + hooks: the time the client waited' },
  { key: 'hooks', header: 'Executions', title: 'Hook calls of this request - expand to see every single one' }
]

/** Rows whose hook executions are unfolded inline, by log entry id. */
const expandedIds = ref<string[]>([])

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

const summary = computed(() => {
  if (!hasActiveTenant.value) return 'No tenant selected'
  if (loading.value) return 'Loading logs…'
  if (total.value === 0) return '0 requests logged'
  const from = (page.value - 1) * pageSize.value + 1
  const to = Math.min(page.value * pageSize.value, total.value)
  return `${from}–${to} of ${total.value} requests`
})

let searchTimer: ReturnType<typeof setTimeout> | undefined

watch([page, pageSize, statusFilter, hookFilter, typeFilter, hasActiveTenant], () => {
  if (hasActiveTenant.value) {
    newEntries.value = 0
    refreshLogs()
  }
})

/**
 * Live mode only ever appends to the newest page - a delta on top of page five would be a lie
 * about what the user is looking at - so switching it on returns to page one.
 */
watch(live, (enabled) => {
  newEntries.value = 0

  if (!enabled) {
    stopLiveTimer()
    return
  }

  if (page.value !== 1) {
    page.value = 1
  } else {
    refreshLogs()
  }

  startLiveTimer()
})

/** A tenant switch invalidates every entry on screen, so the delta cursor goes with it. */
watch(hasActiveTenant, (active) => {
  if (!active) {
    live.value = false
  }
})

watch(search, () => {
  if (!hasActiveTenant.value) {
    return
  }

  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    page.value = 1
    refreshLogs()
  }, 250)
})

onMounted(async () => {
  document.addEventListener('visibilitychange', onVisibilityChange)
  await load(true)
  if (hasActiveTenant.value) {
    await refreshLogs()
  }
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange)
  stopLiveTimer()
  clearTimeout(searchTimer)
})

/**
 * A hidden tab has nobody watching it, so it stops asking. Coming back asks immediately instead
 * of waiting out the interval, otherwise the first thing the user sees is stale.
 */
function onVisibilityChange() {
  if (!live.value) {
    return
  }

  if (document.visibilityState === 'hidden') {
    stopLiveTimer()
  } else {
    startLiveTimer()
    pollNewEntries()
  }
}

function startLiveTimer() {
  stopLiveTimer()
  liveTimer = setInterval(pollNewEntries, LIVE_INTERVAL_MS)
}

function stopLiveTimer() {
  clearInterval(liveTimer)
  liveTimer = undefined
}

/**
 * One tick. It is skipped rather than queued whenever the answer could not be shown honestly:
 * during another read, while a detail sheet is open (the table under it must not move), and on
 * any page but the first.
 */
async function pollNewEntries() {
  if (!live.value || !hasActiveTenant.value || loading.value || detailOpen.value || page.value !== 1) {
    return
  }

  const newest = logs.value[0]?.timestamp
  if (!newest) {
    await refreshLogs()
    return
  }

  try {
    const delta = await api.listRequestLogsSince(
      newest,
      pageSize.value,
      search.value,
      statusFilter.value,
      hookFilter.value,
      typeFilter.value
    )

    // The server bound is inclusive, so the entry the cursor points at comes back with it.
    const known = new Set(logs.value.map((entry) => entry.id))
    const fresh = (delta.items as RequestLogEntry[]).filter((entry) => !known.has(entry.id))
    if (fresh.length === 0) {
      return
    }

    // A full delta means more arrived than one page holds: what is between the last entry of the
    // delta and the first entry on screen is unknown, and prepending would invent continuity.
    if (delta.items.length >= delta.limit) {
      await refreshLogs()
      newEntries.value += fresh.length
      return
    }

    logs.value = [...fresh, ...logs.value].slice(0, pageSize.value)
    total.value += fresh.length
    newEntries.value += fresh.length
  } catch (error) {
    // A dead session or a server that is gone would otherwise be asked again every few seconds,
    // and the session guard would fire on every one of them.
    live.value = false

    if (!(error instanceof ApiError && error.sessionExpired)) {
      toast.add({
        title: error instanceof Error ? error.message : 'Live mode stopped',
        description: 'Live mode was turned off.',
        color: 'error',
        icon: 'i-lucide-circle-x'
      })
    }
  }
}

async function refreshLogs() {
  if (!hasActiveTenant.value) {
    logs.value = []
    total.value = 0
    return
  }

  loading.value = true
  expandedIds.value = []
  try {
    const data = await api.listRequestLogs(
      (page.value - 1) * pageSize.value,
      pageSize.value,
      search.value,
      statusFilter.value,
      hookFilter.value,
      typeFilter.value
    )
    logs.value = data.items as RequestLogEntry[]
    total.value = data.total
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to load logs',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    loading.value = false
  }
}

function clearNewEntries() {
  newEntries.value = 0
}

function setStatusFilter(value: 'all' | 'success' | 'error') {
  statusFilter.value = value
  page.value = 1
}

function setHookFilter(value: 'any' | 'continued' | 'blocked') {
  hookFilter.value = value
  page.value = 1
}

function setTypeFilter(value: 'all' | 'request' | 'hook') {
  typeFilter.value = value
  page.value = 1
}

function formatTimestamp(value: string) {
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

function copyValue(value: string) {
  navigator.clipboard.writeText(value)
  toast.add({
    title: 'Copied to clipboard',
    color: 'success',
    icon: 'i-lucide-clipboard-check'
  })
}

function openDetail(entry: RequestLogEntry) {
  selectedEntry.value = entry
  detailOpen.value = true
}

function statusColor(code: number) {
  if (code >= 500) return 'error'
  if (code >= 400) return 'warning'
  return 'success'
}

function isHookEntry(entry: RequestLogEntry) {
  return entry.type === 'hook'
}

function hookInvocations(entry: RequestLogEntry) {
  return entry.hooks ?? []
}

function hookCount(entry: RequestLogEntry) {
  return entry.hookCount ?? hookInvocations(entry).length
}

/**
 * What the request cost without its hooks. Hooks are remote calls Paprika only waits for, so the
 * split is the difference between "Paprika is slow" and "your hook is". An async hook entry has
 * no Paprika share at all - it is nothing but the hook call.
 */
function appTimeMs(entry: RequestLogEntry) {
  if (entry.execTimeMs == null || isHookEntry(entry)) {
    return null
  }
  return Math.max(0, entry.execTimeMs - (entry.hookTotalMs ?? 0))
}

function formatMs(value?: number | null) {
  return value != null ? `${value}ms` : '—'
}

function toggleExpanded(id: string) {
  expandedIds.value = expandedIds.value.includes(id)
    ? expandedIds.value.filter((entryId) => entryId !== id)
    : [...expandedIds.value, id]
}

function isExpanded(id: string) {
  return expandedIds.value.includes(id)
}

function outcomeColor(outcome: string) {
  switch (outcome) {
    case 'blocked': return 'warning'
    case 'failed': return 'error'
    case 'failedOpen': return 'warning'
    case 'issuedToken': return 'primary'
    default: return 'success'
  }
}

/** One dot per hook call, so a row with five hooks looks different from a row with one. */
function outcomeDotClass(outcome: string) {
  switch (outcome) {
    case 'blocked': return 'bg-warning'
    case 'failed': return 'bg-error'
    case 'failedOpen': return 'bg-warning'
    case 'issuedToken': return 'bg-primary'
    default: return 'bg-success'
  }
}

/** Pulls every entry of one request together: the call itself and the async hooks it spawned. */
function showRelated(requestId: string) {
  search.value = requestId
  typeFilter.value = 'all'
  page.value = 1
}
</script>

<template>
  <div class="space-y-4">
    <UAlert
      v-if="!hasActiveTenant"
      color="primary"
      variant="soft"
      icon="i-lucide-globe"
      title="Select a tenant"
      description="Choose a tenant in the sidebar to view API request logs for that tenant."
    />

    <UCard>
      <template v-if="hasActiveTenant">
        <div class="mb-4 flex flex-col gap-3">
          <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div class="flex flex-col gap-3 sm:flex-row sm:items-center">
            <UInput
              v-model="search"
              class="w-full sm:max-w-md"
              icon="i-lucide-search"
              placeholder="Search URL, method, error, or request ID…"
            />

            <div class="flex items-center gap-1 rounded-lg border border-default p-1">
              <UButton
                size="sm"
                :color="statusFilter === 'all' ? 'primary' : 'neutral'"
                :variant="statusFilter === 'all' ? 'soft' : 'ghost'"
                @click="setStatusFilter('all')"
              >
                All
              </UButton>
              <UButton
                size="sm"
                :color="statusFilter === 'success' ? 'success' : 'neutral'"
                :variant="statusFilter === 'success' ? 'soft' : 'ghost'"
                @click="setStatusFilter('success')"
              >
                Success
              </UButton>
              <UButton
                size="sm"
                :color="statusFilter === 'error' ? 'error' : 'neutral'"
                :variant="statusFilter === 'error' ? 'soft' : 'ghost'"
                @click="setStatusFilter('error')"
              >
                Errors
              </UButton>
            </div>

            <div class="flex items-center gap-1 rounded-lg border border-default p-1">
              <UButton
                size="sm"
                :color="hookFilter === 'any' ? 'primary' : 'neutral'"
                :variant="hookFilter === 'any' ? 'soft' : 'ghost'"
                @click="setHookFilter('any')"
              >
                Any hook
              </UButton>
              <UButton
                size="sm"
                :color="hookFilter === 'continued' ? 'primary' : 'neutral'"
                :variant="hookFilter === 'continued' ? 'soft' : 'ghost'"
                title="A hook ran and let the request through"
                @click="setHookFilter('continued')"
              >
                Hook continued
              </UButton>
              <UButton
                size="sm"
                :color="hookFilter === 'blocked' ? 'warning' : 'neutral'"
                :variant="hookFilter === 'blocked' ? 'soft' : 'ghost'"
                title="A hook ran and rejected the request"
                @click="setHookFilter('blocked')"
              >
                Hook blocked
              </UButton>
            </div>

            <div class="flex items-center gap-1 rounded-lg border border-default p-1">
              <UButton
                size="sm"
                :color="typeFilter === 'all' ? 'primary' : 'neutral'"
                :variant="typeFilter === 'all' ? 'soft' : 'ghost'"
                @click="setTypeFilter('all')"
              >
                All entries
              </UButton>
              <UButton
                size="sm"
                :color="typeFilter === 'request' ? 'primary' : 'neutral'"
                :variant="typeFilter === 'request' ? 'soft' : 'ghost'"
                @click="setTypeFilter('request')"
              >
                Requests
              </UButton>
              <UButton
                size="sm"
                :color="typeFilter === 'hook' ? 'primary' : 'neutral'"
                :variant="typeFilter === 'hook' ? 'soft' : 'ghost'"
                @click="setTypeFilter('hook')"
                title="Entries written by asynchronous after-hooks, which run once the response is out"
              >
                Async hooks
              </UButton>
            </div>

            <div class="flex items-center gap-2">
              <USwitch
                v-model="live"
                label="Live"
                :disabled="!hasActiveTenant"
                title="Poll for new entries every few seconds and add them on top"
              />
              <UBadge
                v-if="live && newEntries > 0"
                color="primary"
                variant="subtle"
                size="md"
                class="cursor-pointer"
                title="Entries added since live mode was enabled - click to reset"
                @click="clearNewEntries"
              >
                +{{ newEntries }} new
              </UBadge>
            </div>
          </div>
            <p class="shrink-0 text-sm text-muted">{{ summary }}</p>
          </div>
        </div>

        <div class="overflow-x-auto rounded-lg border border-default">
          <table class="min-w-full divide-y divide-default text-base">
            <thead class="bg-muted/40">
              <tr class="border-b border-default/60">
                <th
                  v-for="group in columnGroups"
                  :key="group.key"
                  :colspan="group.span"
                  :title="group.title"
                  class="border-l border-default/60 px-3 pt-2 pb-1 text-left text-xs font-semibold uppercase tracking-wide text-dimmed first:border-l-0"
                >
                  {{ group.label }}
                </th>
              </tr>
              <tr>
                <th
                  v-for="column in columns"
                  :key="column.key"
                  :title="column.title"
                  class="px-3 pb-2.5 pt-1 text-left text-sm font-semibold text-muted"
                  :class="{
                    'border-l border-default/60': column.key === 'appTimeMs' || column.key === 'hooks'
                  }"
                >
                  {{ column.header }}
                </th>
                <th class="px-3 pb-2.5 pt-1"><span class="sr-only">Details</span></th>
              </tr>
            </thead>
            <tbody class="divide-y divide-default bg-default">
              <tr v-if="loading">
                <td :colspan="columns.length + 1" class="px-3 py-8 text-center text-muted">
                  Loading…
                </td>
              </tr>
              <tr v-else-if="logs.length === 0">
                <td :colspan="columns.length + 1" class="px-3 py-8 text-center text-muted">
                  No requests logged yet for this tenant.
                </td>
              </tr>
              <template v-for="entry in logs" :key="entry.id">
              <tr
                class="cursor-pointer hover:bg-muted/20"
                :class="{
                  'bg-muted/30': selectedEntry?.id === entry.id && detailOpen,
                  'bg-primary/5': isHookEntry(entry)
                }"
                @click="openDetail(entry)"
              >
                <td class="whitespace-nowrap px-3 py-2.5 font-mono text-sm">
                  <span
                    v-if="isHookEntry(entry)"
                    class="mr-1 text-primary"
                    title="Async hook of an earlier request"
                  >↳</span>
                  {{ formatTimestamp(entry.timestamp) }}
                </td>
                <td class="whitespace-nowrap px-3 py-2.5">
                  <UBadge :color="isHookEntry(entry) ? 'primary' : 'neutral'" variant="soft" size="md">
                    {{ entry.method }}
                  </UBadge>
                </td>
                <td class="max-w-md truncate px-3 py-2.5 font-mono text-sm" :title="entry.url">
                  {{ entry.url }}
                </td>
                <td class="whitespace-nowrap px-3 py-2.5">
                  <UBadge
                    :color="statusColor(entry.statusCode)"
                    variant="soft"
                    size="md"
                    :title="entry.errorMessage
                      ? entry.errorMessage + ' - open the row for the full error'
                      : undefined"
                  >
                    {{ entry.statusCode }}
                  </UBadge>
                </td>
                <td class="px-3 py-2.5">
                  <div v-if="entry.userId" class="flex flex-wrap items-center gap-1.5">
                    <button
                      class="max-w-[16rem] truncate font-mono text-sm hover:text-primary"
                      :title="'Click to copy: ' + entry.userId"
                      @click.stop="copyValue(entry.userId!)"
                    >
                      {{ entry.userId }}
                    </button>
                    <UBadge
                      :color="entry.userRole === 'superadmin' ? 'neutral' : 'primary'"
                      :variant="entry.userRole === 'superadmin' ? 'outline' : 'subtle'"
                      size="md"
                    >
                      {{ entry.userRole === 'superadmin' ? 'superadmin' : 'user' }}
                    </UBadge>
                    <UBadge
                      v-if="entry.apiKeyId"
                      color="warning"
                      variant="subtle"
                      size="md"
                      :title="'Authenticated with API key ' + (entry.apiKeyName || entry.apiKeyId)"
                    >
                      key: {{ entry.apiKeyName || entry.apiKeyId }}
                    </UBadge>
                    <UBadge
                      v-if="entry.rulesBypassed"
                      color="error"
                      variant="subtle"
                      size="md"
                      title="This request skipped the collection rules (rule-bypassing API key)"
                    >
                      rules bypassed
                    </UBadge>
                  </div>
                  <span v-else class="text-muted">—</span>
                </td>
                <td class="whitespace-nowrap border-l border-default/60 px-3 py-2.5 font-mono text-sm text-muted">
                  {{ formatMs(appTimeMs(entry)) }}
                </td>
                <td
                  class="whitespace-nowrap px-3 py-2.5 font-mono text-sm"
                  :class="entry.hookTotalMs ? 'text-warning' : 'text-muted'"
                >
                  {{ formatMs(entry.hookTotalMs) }}
                </td>
                <td class="whitespace-nowrap px-3 py-2.5 font-mono text-sm font-semibold">
                  {{ formatMs(entry.execTimeMs) }}
                </td>
                <td class="whitespace-nowrap border-l border-default/60 px-3 py-2.5">
                  <div v-if="isHookEntry(entry)" class="flex flex-wrap items-center gap-1.5">
                    <UBadge color="primary" variant="subtle" size="md" title="Written by an asynchronous after-hook">
                      async hook
                    </UBadge>
                    <UButton
                      v-if="entry.requestId"
                      size="xs"
                      color="neutral"
                      variant="ghost"
                      icon="i-lucide-link"
                      title="Show every entry of this request"
                      @click.stop="showRelated(entry.requestId!)"
                    />
                  </div>
                  <div v-else-if="hookCount(entry) > 0" class="flex flex-wrap items-center gap-1.5">
                    <UButton
                      size="xs"
                      :color="entry.hookBlocked ? 'warning' : 'neutral'"
                      variant="soft"
                      :icon="isExpanded(entry.id) ? 'i-lucide-chevron-down' : 'i-lucide-chevron-right'"
                      :title="isExpanded(entry.id) ? 'Hide hook executions' : 'Show the ' + hookCount(entry) + ' hook execution(s) of this request'"
                      @click.stop="toggleExpanded(entry.id)"
                    >
                      {{ hookCount(entry) }} {{ hookCount(entry) === 1 ? 'hook' : 'hooks' }}
                    </UButton>
                    <span v-if="hookInvocations(entry).length" class="flex items-center gap-1">
                      <span
                        v-for="(invocation, index) in hookInvocations(entry)"
                        :key="`${entry.id}-dot-${index}`"
                        class="size-1.5 rounded-full"
                        :class="outcomeDotClass(invocation.outcome)"
                        :title="`${invocation.name}: ${invocation.outcome} (${invocation.durationMs} ms)`"
                      />
                    </span>
                    <UBadge v-if="entry.hookBlocked" color="warning" variant="soft" size="md">blocked</UBadge>
                    <UBadge v-else color="success" variant="soft" size="md">continued</UBadge>
                  </div>
                  <span v-else class="text-muted">—</span>
                </td>
                <td class="whitespace-nowrap px-3 py-2.5 text-right">
                  <UButton
                    size="xs"
                    color="neutral"
                    variant="ghost"
                    icon="i-lucide-panel-right-open"
                    title="Show details"
                    @click.stop="openDetail(entry)"
                  />
                </td>
              </tr>

              <!-- One line per hook call, so "3 hooks" can be taken apart without opening the sheet. -->
              <tr v-if="isExpanded(entry.id)" class="border-t-0! bg-muted/10">
                <td :colspan="columns.length + 1" class="px-3 py-2">
                  <ol class="space-y-1.5">
                    <li
                      v-for="(invocation, index) in hookInvocations(entry)"
                      :key="`${entry.id}-hook-${index}`"
                      class="flex flex-wrap items-center gap-2 text-sm"
                    >
                      <span class="w-5 shrink-0 font-mono text-xs text-dimmed">{{ index + 1 }}.</span>
                      <span class="font-medium">{{ invocation.name }}</span>
                      <span class="font-mono text-xs text-muted">
                        {{ invocation.event }}
                        <template v-if="invocation.target"> · {{ invocation.target }}</template>
                        <template v-if="invocation.status"> · HTTP {{ invocation.status }}</template>
                      </span>
                      <UBadge :color="outcomeColor(invocation.outcome)" variant="soft" size="md">
                        {{ invocation.outcome }}
                      </UBadge>
                      <span class="ml-auto font-mono text-sm text-muted">{{ invocation.durationMs }}ms</span>
                    </li>
                  </ol>
                  <p v-if="!hookInvocations(entry).length" class="text-sm text-muted">
                    No per-hook details were recorded for this entry.
                  </p>
                </td>
              </tr>
              </template>
            </tbody>
          </table>
        </div>

        <div class="mt-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <USelect
            v-model="pageSize"
            :items="pageSizeOptions"
            class="w-full sm:w-28"
            value-key="value"
            label-key="label"
          />

          <div class="flex items-center justify-end gap-2">
            <UButton
              variant="soft"
              color="neutral"
              icon="i-lucide-chevron-left"
              :disabled="page <= 1 || loading || live"
              :title="live ? 'Turn off live mode to page through the log' : undefined"
              @click="page--"
            />
            <span class="text-sm text-muted">Page {{ page }} / {{ pageCount }}</span>
            <UButton
              variant="soft"
              color="neutral"
              icon="i-lucide-chevron-right"
              :disabled="page >= pageCount || loading || live"
              :title="live ? 'Turn off live mode to page through the log' : undefined"
              @click="page++"
            />
          </div>
        </div>
      </template>
    </UCard>

    <RequestLogDetailSheet
      v-model:open="detailOpen"
      :entry="selectedEntry"
      @copy="copyValue"
    />
  </div>
</template>
