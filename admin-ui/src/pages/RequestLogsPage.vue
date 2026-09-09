<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '@/lib/api'
import { useAppToast } from '@/composables/useAppToast'
import { useBootstrap } from '@/composables/useBootstrap'
import type { RequestLogEntry } from '@/types'

const toast = useAppToast()
const { load, bootstrap } = useBootstrap()

const logs = ref<RequestLogEntry[]>([])
const total = ref(0)
const loading = ref(false)
const search = ref('')
const statusFilter = ref<'all' | 'success' | 'error'>('all')
const hookFilter = ref<'any' | 'fired' | 'blocked'>('any')
const page = ref(1)
const pageSize = ref(50)

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

watch([page, pageSize, statusFilter, hookFilter, hasActiveTenant], () => {
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
      hookFilter.value
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

function copyUserId(id: string) {
  navigator.clipboard.writeText(id)
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
          </div>
            <p class="shrink-0 text-sm text-muted">{{ summary }}</p>
          </div>
        </div>

        <div class="overflow-x-auto rounded-lg border border-default">
          <table class="min-w-full divide-y divide-default text-sm">
            <thead class="bg-muted/40">
              <tr>
                <th
                  v-for="column in columns"
                  :key="column.key"
                  class="px-3 py-2 text-left font-medium text-muted"
                >
                  {{ column.header }}
                </th>
              </tr>
            </thead>
            <tbody class="divide-y divide-default bg-default">
              <tr v-if="loading">
                <td :colspan="columns.length" class="px-3 py-8 text-center text-muted">
                  Loading…
                </td>
              </tr>
              <tr v-else-if="logs.length === 0">
                <td :colspan="columns.length" class="px-3 py-8 text-center text-muted">
                  No requests logged yet for this tenant.
                </td>
              </tr>
              <tr v-for="entry in logs" :key="entry.id" class="hover:bg-muted/20">
                <td class="whitespace-nowrap px-3 py-2 font-mono text-xs">
                  {{ formatTimestamp(entry.timestamp) }}
                </td>
                <td class="whitespace-nowrap px-3 py-2">
                  <UBadge color="neutral" variant="soft" size="sm">{{ entry.method }}</UBadge>
                </td>
                <td class="max-w-md truncate px-3 py-2 font-mono text-xs" :title="entry.url">
                  {{ entry.url }}
                </td>
                <td class="whitespace-nowrap px-3 py-2">
                  <UBadge :color="statusColor(entry.statusCode)" variant="soft" size="sm">
                    {{ entry.statusCode }}
                  </UBadge>
                </td>
                <td class="whitespace-nowrap px-3 py-2 font-mono text-xs text-muted">
                  {{ entry.execTimeMs != null ? `${entry.execTimeMs}ms` : '—' }}
                </td>
                <td class="px-3 py-2">
                  <div v-if="entry.userId" class="flex items-center gap-1.5">
                    <button
                      class="font-mono text-xs text-muted hover:text-default"
                      :title="'Click to copy: ' + entry.userId"
                      @click="copyUserId(entry.userId!)"
                    >
                      {{ entry.userId }}
                    </button>
                    <UBadge
                      :color="entry.userRole === 'superadmin' ? 'neutral' : 'primary'"
                      :variant="entry.userRole === 'superadmin' ? 'outline' : 'subtle'"
                      size="xs"
                    >
                      {{ entry.userRole === 'superadmin' ? 'superadmin' : 'user' }}
                    </UBadge>
                  </div>
                  <span v-else class="text-muted">—</span>
                </td>
                <td class="whitespace-nowrap px-3 py-2">
                  <UBadge v-if="entry.hookBlocked" color="warning" variant="soft" size="sm">blocked</UBadge>
                  <UBadge v-else-if="entry.hookFired" color="success" variant="soft" size="sm">fired</UBadge>
                  <span v-else class="text-muted">—</span>
                </td>
                <td class="max-w-xs truncate px-3 py-2 text-sm" :title="entry.errorMessage || ''">
                  {{ entry.errorMessage || '—' }}
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
  </div>
</template>
