<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '@/lib/api'
import { formatCellValue } from '@/lib/utils'
import { modalUi } from '@/lib/overlay-ui'
import { useAppToast } from '@/composables/useAppToast'
import { applyDefaultsToRecord, booleanToFormValue, BOOLEAN_UNSET } from '@/lib/field-validation'
import TenantUsersManager from '@/components/TenantUsersManager.vue'
import type { RecordSavePayload } from '@/components/RecordEditorSheet.vue'
import type { CollectionDefinition } from '@/types'

const route = useRoute()
const toast = useAppToast()

const collection = computed(() => String(route.params.collection))
const isUsers = computed(() => collection.value === 'users')
const definition = ref<CollectionDefinition | null>(null)
const records = ref<Record<string, unknown>[]>([])
const total = ref(0)
const loading = ref(true)
const search = ref('')
const sortField = ref('updatedAt')
const sortDirection = ref<'asc' | 'desc'>('desc')
const page = ref(1)
const pageSize = ref(25)
const selectedIds = ref<Set<string>>(new Set())
const editorOpen = ref(false)
const editorMode = ref<'new' | 'edit'>('new')
const editorRecord = ref<Record<string, unknown>>({})
const saving = ref(false)
const bulkDeleteOpen = ref(false)
const recordDeleteOpen = ref(false)

const pageSizeOptions = [
  { label: '10', value: 10 },
  { label: '25', value: 25 },
  { label: '50', value: 50 },
  { label: '100', value: 100 }
]

const sortOptions = computed(() => {
  const fields = definition.value?.fields || []
  return [
    { label: 'Updated', value: 'updatedAt' },
    { label: 'Created', value: 'createdAt' },
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
    { accessorKey: 'updatedAt', header: 'updatedAt' }
  ]
})

const filteredRecords = computed(() => {
  let items = [...records.value]
  const query = search.value.trim().toLowerCase()

  if (query) {
    items = items.filter((record) => JSON.stringify(record).toLowerCase().includes(query))
  }

  items.sort((a, b) => {
    const left = a[sortField.value]
    const right = b[sortField.value]
    const compare = String(left ?? '').localeCompare(String(right ?? ''), undefined, {
      numeric: true
    })
    return sortDirection.value === 'asc' ? compare : -compare
  })

  return items
})

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

const summary = computed(() => {
  if (loading.value) return 'Loading records…'
  if (total.value === 0) return '0 records'
  const from = (page.value - 1) * pageSize.value + 1
  const to = Math.min(page.value * pageSize.value, total.value)
  return `${from}–${to} of ${total.value} records`
})

watch([collection, page, pageSize], () => {
  if (isUsers.value) return
  refreshRecords()
})

onMounted(async () => {
  window.addEventListener('paprika:new-collection', onNewCollectionShortcut as EventListener)
  if (isUsers.value) return
  await loadDefinition()
  await refreshRecords()
})

onUnmounted(() => {
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
  loading.value = true
  selectedIds.value = new Set()
  try {
    const offset = (page.value - 1) * pageSize.value
    const result = await api.listRecords(collection.value, offset, pageSize.value)
    records.value = result.items
    total.value = result.total
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to load records',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    loading.value = false
  }
}

function toggleAll(checked: boolean) {
  const next = new Set(selectedIds.value)
  for (const record of filteredRecords.value) {
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
  const record: Record<string, unknown> = {}
  for (const field of definition.value?.fields || []) {
    if (field.type === 'FILE' || field.type === 'BOOLEAN') continue
    record[field.name] = ''
  }
  const withDefaults = applyDefaultsToRecord(definition.value?.fields || [], record)
  for (const field of definition.value?.fields || []) {
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
  toast.add({ title: message, color: 'error', icon: 'i-lucide-circle-x' })
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
  if (!editorRecord.value.id) return
  saving.value = true
  try {
    await api.deleteRecord(collection.value, String(editorRecord.value.id))
    recordDeleteOpen.value = false
    editorOpen.value = false
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
  recordDeleteOpen.value = true
}

async function bulkDelete() {
  saving.value = true
  try {
    await Promise.all(
      Array.from(selectedIds.value).map((id) => api.deleteRecord(collection.value, id))
    )
    bulkDeleteOpen.value = false
    toast.add({
      title:
        selectedIds.value.size === 1
          ? 'Record deleted'
          : `${selectedIds.value.size} records deleted`,
      color: 'success',
      icon: 'i-lucide-circle-check'
    })
    await refreshRecords()
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to delete records',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    saving.value = false
  }
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

      <UTable :data="filteredRecords" :columns="columns" :loading="loading">
        <template #select-header>
          <UCheckbox
            :model-value="
              filteredRecords.length > 0 &&
              filteredRecords.every((record) => selectedIds.has(String(record.id)))
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
          <button
            class="font-mono text-sm text-primary hover:underline"
            @click="openEditRecord(row.original)"
          >
            {{ row.original.id }}
          </button>
        </template>
        <template
          v-for="field in definition?.fields || []"
          :key="field.name"
          #[`${field.name}-cell`]="{ row }"
        >
          <button class="w-full text-left" @click="openEditRecord(row.original)">
            {{ formatCellValue(row.original[field.name], field.type) }}
          </button>
        </template>
        <template #createdAt-cell="{ row }">
          <span class="font-mono text-sm text-muted">{{ row.original.createdAt }}</span>
        </template>
        <template #updatedAt-cell="{ row }">
          <span class="font-mono text-sm text-muted">{{ row.original.updatedAt }}</span>
        </template>
        <template #empty>
          <div class="py-10 text-center text-muted">No records found.</div>
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
            Delete record <code>{{ editorRecord.id }}</code>? This cannot be undone.
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
