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
const page = ref(1)
const pageSize = ref(50)

const hasActiveTenant = computed(() => !!bootstrap.value?.hasActiveTenant)

const pageSizeOptions = [
  { label: '25', value: 25 },
  { label: '50', value: 50 },
  { label: '100', value: 100 }
]

const statusOptions = [
  { label: 'All requests', value: 'all' },
  { label: 'Success only', value: 'success' },
  { label: 'Errors only', value: 'error' }
]

const columns = [
  { accessorKey: 'timestamp', header: 'Timestamp' },
  { accessorKey: 'method', header: 'Method' },
  { accessorKey: 'url', header: 'URL' },
  { accessorKey: 'statusCode', header: 'Status' },
  { accessorKey: 'errorMessage', header: 'Error' }
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

watch([page, pageSize, statusFilter, hasActiveTenant], () => {
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
      statusFilter.value
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

function formatTimestamp(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleString()
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
      <template #header>
        <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 class="font-semibold">Logs</h2>
            <p v-if="hasActiveTenant" class="text-sm text-muted">
              API requests for tenant
              <span class="font-medium text-default">{{ bootstrap?.activeTenant?.name }}</span>
              — metadata only, no payloads.
            </p>
            <p v-else class="text-sm text-muted">
              API request metadata per tenant — no payloads.
            </p>
          </div>
          <p class="text-sm text-muted">{{ summary }}</p>
        </div>
      </template>

      <template v-if="hasActiveTenant">
        <div class="mb-4 flex flex-col gap-3 sm:flex-row sm:items-center">
          <UInput
            v-model="search"
            class="w-full sm:max-w-md"
            icon="i-lucide-search"
            placeholder="Search URL, method, or error…"
          />
          <USelect
            v-model="statusFilter"
            :items="statusOptions"
            class="w-full sm:w-48"
            value-key="value"
            label-key="label"
          />
        </div>

        <div class="overflow-x-auto rounded-lg border border-default">
          <table class="min-w-full divide-y divide-default text-sm">
            <thead class="bg-muted/40">
              <tr>
                <th
                  v-for="column in columns"
                  :key="column.accessorKey"
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
                  <UBadge color="neutral" variant="soft" size="xs">{{ entry.method }}</UBadge>
                </td>
                <td class="max-w-md truncate px-3 py-2 font-mono text-xs" :title="entry.url">
                  {{ entry.url }}
                </td>
                <td class="whitespace-nowrap px-3 py-2">
                  <UBadge :color="statusColor(entry.statusCode)" variant="soft" size="xs">
                    {{ entry.statusCode }}
                  </UBadge>
                </td>
                <td class="max-w-xs truncate px-3 py-2 text-muted" :title="entry.errorMessage || ''">
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
