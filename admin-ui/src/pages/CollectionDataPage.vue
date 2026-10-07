<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouteCollection } from '@/composables/useRouteCollection'
import { api } from '@/lib/api'
import { bulkOutcome, formatCellValue, truncateCellText } from '@/lib/utils'
import { modalUi } from '@/lib/overlay-ui'
import { useAppToast } from '@/composables/useAppToast'
import { usePageSize } from '@/composables/usePageSize'
import { applyDefaultsToRecord, booleanToFormValue, BOOLEAN_UNSET } from '@/lib/field-validation'
import TenantUsersManager from '@/components/TenantUsersManager.vue'
import type { RecordSavePayload } from '@/components/RecordEditorSheet.vue'
import type { CollectionDefinition, FieldDefinition } from '@/types'

const toast = useAppToast()

const collection = useRouteCollection()
const isUsers = computed(() => collection.value === 'users')
const definition = ref<CollectionDefinition | null>(null)
const records = ref<Record<string, unknown>[]>([])
const total = ref(0)
const loading = ref(true)
const search = ref('')
const sortField = ref('createdAt')
const sortDirection = ref<'asc' | 'desc'>('desc')
const page = ref(1)
const selectedIds = ref<Set<string>>(new Set())
const editorOpen = ref(false)
const editorMode = ref<'new' | 'edit'>('new')
const editorRecord = ref<Record<string, unknown>>({})
const saving = ref(false)
const bulkDeleteOpen = ref(false)
const recordDeleteOpen = ref(false)
// Reached from the editor sheet and a row action, so it cannot be read off the editor state.
const deletingRecord = ref<Record<string, unknown> | null>(null)

/** Identifies the newest record request so an overtaken one cannot write its result. */
let latestRecordsRequest = 0

/** The search the records on screen were loaded with; typing applies it after a short pause. */
const appliedSearch = ref('')
let searchTimer: ReturnType<typeof setTimeout> | undefined

const pageSizeOptions = [
  { label: '10', value: 10 },
  { label: '25', value: 25 },
  { label: '50', value: 50 },
  { label: '100', value: 100 }
]
const pageSize = usePageSize('records', 25, pageSizeOptions)

const sortOptions = computed(() => {
  // The server rejects sorting on JSON and FILE fields, they have no meaningful order
  const fields = (definition.value?.fields || []).filter(
    (field) => field.type !== 'JSON' && field.type !== 'FILE'
  )
  return [
    { label: 'Created', value: 'createdAt' },
    { label: 'Updated', value: 'updatedAt' },
    { label: 'ID', value: 'id' },
    ...fields.map((field) => ({ label: field.name, value: field.name }))
  ]
})

const columns = computed(() => {
  const fields = definition.value?.fields || []
  return [
    { id: 'select', header: '' },
    { accessorKey: 'id', header: 'id' },
    ...fields.map((field) => ({ accessorKey: field.name, header: field.name })),
    { accessorKey: 'createdAt', header: 'createdAt' },
    { accessorKey: 'updatedAt', header: 'updatedAt' },
    { id: 'actions', header: '' }
  ]
})

function cellText(record: Record<string, unknown>, field: FieldDefinition): string {
  return truncateCellText(formatCellValue(record[field.name], field.type))
}

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

const summary = computed(() => {
  if (loading.value) return 'Loading records…'
  if (total.value === 0) return '0 records'
  const from = (page.value - 1) * pageSize.value + 1
  const to = Math.min(page.value * pageSize.value, total.value)
  return `${from}–${to} of ${total.value} records`
})

// Switching collections keeps this component mounted, so everything derived from the previous one -
// above all the definition, which drives columns and sort options - has to be reset by hand.
watch(collection, async () => {
  if (isUsers.value) return

  definition.value = null
  records.value = []
  total.value = 0
  // A sort field that does not exist in the new collection would silently sort on nothing.
  search.value = ''
  appliedSearch.value = ''
  clearTimeout(searchTimer)
  sortField.value = 'createdAt'
  sortDirection.value = 'desc'
  page.value = 1

  await loadDefinition()
  await refreshRecords()
})

// Search runs on the server across all pages; a new search starts again on the first page.
watch(search, (value) => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    if (value.trim() === appliedSearch.value) return
    appliedSearch.value = value.trim()
    if (page.value !== 1) {
      page.value = 1
      return
    }
    refreshRecords()
  }, 300)
})

watch([page, pageSize], () => {
  if (isUsers.value) return
  refreshRecords()
})

// A new order makes the current page position meaningless; resetting the page reloads via its watcher.
watch([sortField, sortDirection], () => {
  if (isUsers.value) return
  if (page.value !== 1) {
    page.value = 1
    return
  }
  refreshRecords()
})

onMounted(() => {
  window.addEventListener('paprika:new-collection', onNewCollectionShortcut as EventListener)
})

onUnmounted(() => {
  clearTimeout(searchTimer)
  window.removeEventListener('paprika:new-collection', onNewCollectionShortcut as EventListener)
})

function onNewCollectionShortcut() {
  /* handled globally */
}

async function loadDefinition() {
  try {
    definition.value = await api.getCollectionDefinition(collection.value)
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to load schema',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  }
}

async function refreshRecords() {
  const request = ++latestRecordsRequest
  loading.value = true
  selectedIds.value = new Set()
  try {
    const offset = (page.value - 1) * pageSize.value
    const result = await api.listRecords(
      collection.value,
      offset,
      pageSize.value,
      `${sortField.value}:${sortDirection.value}`,
      appliedSearch.value
    )
    // Collection switches and quick paging can overlap requests; only the newest may write.
    if (request !== latestRecordsRequest) return
    records.value = result.items
    total.value = result.total
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to load records',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    if (request === latestRecordsRequest) {
      loading.value = false
    }
  }
}

function toggleAll(checked: boolean) {
  const next = new Set(selectedIds.value)
  for (const record of records.value) {
    const id = String(record.id || '')
    if (!id) continue
    if (checked) next.add(id)
    else next.delete(id)
  }
  selectedIds.value = next
}

function toggleOne(id: string, checked: boolean) {
  const next = new Set(selectedIds.value)
  if (checked) next.add(id)
  else next.delete(id)
  selectedIds.value = next
}

function openNewRecord() {
  editorMode.value = 'new'
  editorRecord.value = buildDefaultRecord()
  editorOpen.value = true
}

async function openEditRecord(record: Record<string, unknown>) {
  editorMode.value = 'edit'
  editorOpen.value = true
  editorRecord.value = { id: 'Loading…' }
  try {
    editorRecord.value = await api.getRecord(collection.value, String(record.id))
  } catch (error) {
    editorOpen.value = false
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to load record',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  }
}

function buildDefaultRecord(): Record<string, unknown> {
  const fields = definition.value?.fields || []
  // Defaults first: applyDefaultsToRecord only fills missing fields, so the empty-string
  // placeholders must come after it or they would shadow every default.
  const withDefaults = applyDefaultsToRecord(fields, {})
  for (const field of fields) {
    if (field.type === 'FILE' || field.type === 'BOOLEAN') continue
    if (withDefaults[field.name] === undefined) {
      withDefaults[field.name] = ''
    }
  }
  for (const field of fields) {
    if (field.type !== 'BOOLEAN') continue
    if (field.default !== undefined) {
      withDefaults[field.name] = booleanToFormValue(field.default)
    } else if (field.required) {
      withDefaults[field.name] = 'false'
    } else {
      withDefaults[field.name] = BOOLEAN_UNSET
    }
  }
  return withDefaults
}

function onValidationError(message: string) {
  toast.add({
    title: 'Record not saved',
    description: message,
    color: 'error',
    icon: 'i-lucide-circle-x'
  })
}

async function saveRecord(payload: RecordSavePayload) {
  saving.value = true
  try {
    const hasFiles = Object.values(payload.files).some((files) => files.length > 0)
    if (editorMode.value === 'new') {
      if (hasFiles) {
        await api.saveRecordMultipart(collection.value, payload.values, payload.files)
      } else {
        await api.createRecord(collection.value, payload.values)
      }
      toast.add({ title: 'Record created', color: 'success', icon: 'i-lucide-circle-check' })
    } else {
      if (hasFiles) {
        await api.saveRecordMultipart(
          collection.value,
          payload.values,
          payload.files,
          String(editorRecord.value.id)
        )
      } else {
        await api.updateRecord(collection.value, String(editorRecord.value.id), payload.values)
      }
      toast.add({ title: 'Record saved', color: 'success', icon: 'i-lucide-circle-check' })
    }
    editorOpen.value = false
    await refreshRecords()
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to save record',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    saving.value = false
  }
}

async function deleteCurrentRecord() {
  const id = deletingRecord.value?.id
  if (!id) return
  saving.value = true
  try {
    await api.deleteRecord(collection.value, String(id))
    recordDeleteOpen.value = false
    if (String(editorRecord.value.id || '') === String(id)) {
      editorOpen.value = false
    }
    deletingRecord.value = null
    toast.add({ title: 'Record deleted', color: 'success', icon: 'i-lucide-circle-check' })
    await refreshRecords()
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to delete record',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    saving.value = false
  }
}

function requestDeleteRecord() {
  deletingRecord.value = { ...editorRecord.value }
  recordDeleteOpen.value = true
}

function confirmDeleteRecord(record: Record<string, unknown>) {
  deletingRecord.value = record
  recordDeleteOpen.value = true
}

// Each record is its own request; one failing must not hide the ones that went through.
async function bulkDelete() {
  const ids = Array.from(selectedIds.value)
  saving.value = true
  try {
    const results = await Promise.allSettled(ids.map((id) => api.deleteRecord(collection.value, id)))
    const { failedIds, succeeded, message } = bulkOutcome(ids, results)
    bulkDeleteOpen.value = false
    if (failedIds.length === 0) {
      toast.add({
        title: ids.length === 1 ? 'Record deleted' : `${ids.length} records deleted`,
        color: 'success',
        icon: 'i-lucide-circle-check'
      })
    } else {
      toast.add({
        title: `${succeeded} of ${ids.length} records deleted`,
        description: message,
        color: 'error',
        icon: 'i-lucide-circle-x'
      })
    }
    await refreshRecords()
    // The ones that failed stay selected, ready for another attempt.
    selectedIds.value = new Set(failedIds.filter((id) => records.value.some((r) => String(r.id) === id)))
  } finally {
    saving.value = false
  }
}

// Awaited in setup, so the <Suspense> in App.vue keeps the previous tab on screen until this one
// has its data instead of flashing "Loading records…" first.
if (!isUsers.value) {
  await loadDefinition()
  await refreshRecords()
}
</script>

<template>
  <TenantUsersManager v-if="isUsers" />
  <div v-else class="space-y-4">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <p class="text-sm text-muted">{{ summary }}</p>
      <UButton icon="i-lucide-plus" @click="openNewRecord">New record</UButton>
    </div>

    <UCard>
      <div class="mb-4 flex flex-wrap items-center gap-3">
        <UInput
          v-model="search"
          icon="i-lucide-search"
          placeholder="Search records…"
          class="w-full min-w-0 sm:w-auto sm:min-w-56 sm:flex-1"
        />

        <USelect
          v-model="sortField"
          :items="sortOptions"
          icon="i-lucide-arrow-up-down"
          placeholder="Sort by"
          class="w-full min-w-0 sm:w-auto sm:min-w-40"
        />

        <USelect
          v-model="sortDirection"
          :items="[
            { label: 'Ascending', value: 'asc' },
            { label: 'Descending', value: 'desc' }
          ]"
          icon="i-lucide-list-ordered"
          class="w-full min-w-0 sm:w-auto sm:min-w-36"
        />

        <UButton
          v-if="selectedIds.size > 0"
          color="error"
          variant="soft"
          icon="i-lucide-trash-2"
          @click="bulkDeleteOpen = true"
        >
          Delete selected ({{ selectedIds.size }})
        </UButton>
      </div>

      <UTable
        :data="records"
        :columns="columns"
        :loading="loading"
        :column-pinning="{ left: ['select'], right: ['actions'] }"
      >
        <template #select-header>
          <UCheckbox
            :model-value="
              records.length > 0 &&
              records.every((record) => selectedIds.has(String(record.id)))
            "
            @update:model-value="toggleAll(!!$event)"
          />
        </template>
        <template #select-cell="{ row }">
          <UCheckbox
            :model-value="selectedIds.has(String(row.original.id))"
            @update:model-value="toggleOne(String(row.original.id), !!$event)"
            @click.stop
          />
        </template>
        <template #id-cell="{ row }">
          <div class="flex items-center gap-1">
            <code class="text-sm">{{ row.original.id }}</code>
            <CopyButton :value="String(row.original.id)" label="Copy ID" size="xs" />
          </div>
        </template>
        <template
          v-for="field in definition?.fields || []"
          :key="field.name"
          #[`${field.name}-cell`]="{ row }"
        >
          <FormattedDate
            v-if="field.type === 'DATETIME'"
            :value="row.original[field.name] as string | null"
          />
          <span
            v-else
            class="block max-w-64 truncate text-sm"
            :title="cellText(row.original, field)"
          >
            {{ cellText(row.original, field) }}
          </span>
        </template>
        <template #createdAt-cell="{ row }">
          <FormattedDate :value="row.original.createdAt as string | null" />
        </template>
        <template #updatedAt-cell="{ row }">
          <FormattedDate :value="row.original.updatedAt as string | null" />
        </template>
        <template #actions-cell="{ row }">
          <RowActions
            @edit="openEditRecord(row.original)"
            @delete="confirmDeleteRecord(row.original)"
          />
        </template>
        <template #empty>
          <p v-if="loading" class="py-10 text-center text-sm text-muted">Loading records…</p>
          <div v-else-if="appliedSearch" class="flex flex-col items-center gap-3 py-10 text-center">
            <p class="text-sm text-muted">No records match “{{ appliedSearch }}”.</p>
            <UButton variant="soft" color="neutral" icon="i-lucide-x" @click="search = ''">
              Clear search
            </UButton>
          </div>
          <div v-else class="flex flex-col items-center gap-3 py-10 text-center">
            <p class="text-sm text-muted">No records yet.</p>
            <UButton variant="soft" icon="i-lucide-plus" @click="openNewRecord">New record</UButton>
          </div>
        </template>
      </UTable>

      <div class="mt-4 flex flex-wrap items-center justify-between gap-3 border-t border-default pt-4">
        <div class="flex items-center gap-2 text-sm text-muted">
          <span>Rows per page</span>
          <USelect v-model="pageSize" :items="pageSizeOptions" class="w-24" />
        </div>
        <div class="flex items-center gap-2">
          <UButton
            icon="i-lucide-chevron-left"
            variant="ghost"
            color="neutral"
            :disabled="page <= 1"
            @click="page--"
          />
          <span class="min-w-16 text-center text-sm">{{ page }} / {{ pageCount }}</span>
          <UButton
            icon="i-lucide-chevron-right"
            variant="ghost"
            color="neutral"
            :disabled="page >= pageCount"
            @click="page++"
          />
        </div>
      </div>
    </UCard>

    <RecordEditorSheet
      v-model:open="editorOpen"
      :mode="editorMode"
      :record="editorRecord"
      :fields="definition?.fields || []"
      :saving="saving"
      @save="saveRecord"
      @delete="requestDeleteRecord"
      @validation-error="onValidationError"
    />

    <UModal v-model:open="recordDeleteOpen" portal="body" :ui="modalUi">
      <template #content>
        <UCard>
          <template #header>
            <div class="flex items-center gap-2">
              <UIcon name="i-lucide-triangle-alert" class="size-5 text-error" />
              <h3 class="font-semibold">Delete record</h3>
            </div>
          </template>
          <p class="text-sm text-muted">
            Delete record <code>{{ deletingRecord?.id }}</code>? This cannot be undone.
          </p>
          <template #footer>
            <div class="flex justify-end gap-2">
              <UButton variant="ghost" color="neutral" @click="recordDeleteOpen = false">Cancel</UButton>
              <UButton color="error" :loading="saving" icon="i-lucide-trash-2" @click="deleteCurrentRecord">
                Delete record
              </UButton>
            </div>
          </template>
        </UCard>
      </template>
    </UModal>

    <UModal v-model:open="bulkDeleteOpen" portal="body" :ui="modalUi">
      <template #content>
        <UCard>
          <template #header>
            <div class="flex items-center gap-2">
              <UIcon name="i-lucide-triangle-alert" class="size-5 text-error" />
              <h3 class="font-semibold">Delete records</h3>
            </div>
          </template>
          <p class="text-sm text-muted">
            Delete {{ selectedIds.size }} selected record{{ selectedIds.size === 1 ? '' : 's' }}?
          </p>
          <template #footer>
            <div class="flex justify-end gap-2">
              <UButton variant="ghost" color="neutral" @click="bulkDeleteOpen = false">
                Cancel
              </UButton>
              <UButton color="error" :loading="saving" icon="i-lucide-trash-2" @click="bulkDelete">
                Delete
              </UButton>
            </div>
          </template>
        </UCard>
      </template>
    </UModal>
  </div>
</template>
