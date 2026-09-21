<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '@/lib/api'
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
const hookFilter = ref<'any' | 'fired' | 'blocked'>('any')
const typeFilter = ref<'all' | 'request' | 'hook'>('all')
const page = ref(1)
const pageSize = ref(50)
const detailOpen = ref(false)
const selectedEntry = ref<RequestLogEntry | null>(null)

const hasActiveTenant = computed(() => !!bootstrap.value?.hasActiveTenant)

const pageSizeOptions = [
  { label: '25', value: 25 },
  { label: '50', value: 50 },
  { label: '100', value: 100 }
]

const columns = [
  { key: 'timestamp', header: 'Timestamp' },
  { key: 'method', header: 'Method' },
  { key: 'url', header: 'URL' },
  { key: 'statusCode', header: 'Status' },
  { key: 'execTimeMs', header: 'Time' },
  { key: 'hookTotalMs', header: 'Hook time' },
  { key: 'userId', header: 'User' },
  { key: 'hookFired', header: 'Hook' },
  { key: 'errorMessage', header: 'Error' }
]

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
    refreshLogs()
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
  await load(true)
  if (hasActiveTenant.value) {
    await refreshLogs()
  }
})

async function refreshLogs() {
  if (!hasActiveTenant.value) {
    logs.value = []
    total.value = 0
    return
  }

  loading.value = true
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

function setStatusFilter(value: 'all' | 'success' | 'error') {
  statusFilter.value = value
  page.value = 1
}

function setHookFilter(value: 'any' | 'fired' | 'blocked') {
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
              placeholder="Search URL, method, or error…"
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
                :color="hookFilter === 'fired' ? 'primary' : 'neutral'"
                :variant="hookFilter === 'fired' ? 'soft' : 'ghost'"
                @click="setHookFilter('fired')"
              >
                Hook fired
              </UButton>
              <UButton
                size="sm"
                :color="hookFilter === 'blocked' ? 'warning' : 'neutral'"
                :variant="hookFilter === 'blocked' ? 'soft' : 'ghost'"
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
          </div>
            <p class="shrink-0 text-sm text-muted">{{ summary }}</p>
          </div>
        </div>

        <div class="overflow-x-auto rounded-lg border border-default">
          <table class="min-w-full divide-y divide-default text-base">
            <thead class="bg-muted/40">
              <tr>
                <th
                  v-for="column in columns"
                  :key="column.key"
                  class="px-3 py-2.5 text-left text-sm font-semibold text-muted"
                >
                  {{ column.header }}
                </th>
                <th class="px-3 py-2.5"><span class="sr-only">Details</span></th>
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
              <tr
                v-for="entry in logs"
                :key="entry.id"
                class="cursor-pointer hover:bg-muted/20"
                :class="{ 'bg-muted/30': selectedEntry?.id === entry.id && detailOpen }"
                @click="openDetail(entry)"
              >
                <td class="whitespace-nowrap px-3 py-2.5 font-mono text-sm">
                  {{ formatTimestamp(entry.timestamp) }}
                </td>
                <td class="whitespace-nowrap px-3 py-2.5">
                  <UBadge color="neutral" variant="soft" size="md">{{ entry.method }}</UBadge>
                </td>
                <td class="max-w-md truncate px-3 py-2.5 font-mono text-sm" :title="entry.url">
                  {{ entry.url }}
                </td>
                <td class="whitespace-nowrap px-3 py-2.5">
                  <UBadge :color="statusColor(entry.statusCode)" variant="soft" size="md">
                    {{ entry.statusCode }}
                  </UBadge>
                </td>
                <td class="whitespace-nowrap px-3 py-2.5 font-mono text-sm text-muted">
                  {{ entry.execTimeMs != null ? `${entry.execTimeMs}ms` : '—' }}
                </td>
                <td class="whitespace-nowrap px-3 py-2.5 font-mono text-sm text-muted">
                  {{ entry.hookTotalMs != null ? `${entry.hookTotalMs}ms` : '—' }}
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
                <td class="whitespace-nowrap px-3 py-2.5">
                  <UBadge v-if="entry.hookBlocked" color="warning" variant="soft" size="md">blocked</UBadge>
                  <UBadge v-else-if="entry.hookFired" color="success" variant="soft" size="md">fired</UBadge>
                  <span v-else class="text-muted">—</span>
                </td>
                <td class="max-w-xs truncate px-3 py-2.5 text-sm" :title="entry.errorMessage || ''">
                  {{ entry.errorMessage || '—' }}
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
              :disabled="page <= 1 || loading"
              @click="page--"
            />
            <span class="text-sm text-muted">Page {{ page }} / {{ pageCount }}</span>
            <UButton
              variant="soft"
              color="neutral"
              icon="i-lucide-chevron-right"
              :disabled="page >= pageCount || loading"
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
