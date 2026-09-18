<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { type SchemaRow } from '@/components/SchemaEditorSheet.vue'
import { api } from '@/lib/api'
import { useSchemaEditorSheet, emptyRow } from '@/composables/useSchemaEditorSheet'
import { useAppToast } from '@/composables/useAppToast'
import { modalUi } from '@/lib/overlay-ui'
import { fieldTypeIcon, fieldTypeLabel } from '@/lib/utils'
import IndexEditorSheet from '@/components/IndexEditorSheet.vue'
import { isReservedSchemaFieldName, SYSTEM_RECORD_FIELDS } from '@/lib/system-fields'
import { optionsToSchemaRow, parseSelectValues, schemaRowToField } from '@/lib/schema-options'
import {
  isProtectedUserField,
  isSystemCollection,
  requestDeleteCollection
} from '@/lib/system-collections'
import type { CollectionDefinition, IndexDefinition } from '@/types'

const route = useRoute()
const toast = useAppToast()
const { editorRow, editorMode, editorIndex, saving, openEditor: openSchemaEditor, closeEditor, setSaving } = useSchemaEditorSheet()

const rows = ref<SchemaRow[]>([])
const collection = computed(() => String(route.params.collection))
const isSystem = computed(() => isSystemCollection(collection.value))
const definition = ref<CollectionDefinition | null>(null)
const loading = ref(true)
const fieldDeleteOpen = ref(false)
const fieldDeleteTarget = ref<{ index: number; name: string } | null>(null)

const indexEditorOpen = ref(false)
const indexEditorMode = ref<'add' | 'edit'>('add')
const indexEditorTarget = ref<IndexDefinition | null>(null)
const indexDeleteOpen = ref(false)
const indexDeleteTarget = ref<IndexDefinition | null>(null)

const indexes = computed<IndexDefinition[]>(() => definition.value?.indexes || [])

// An index may also be built on the fields every record carries, which are not part of the
// schema field list.
const indexableFields = computed(() => indexableFieldsFor(rows.value))

const takenIndexNames = computed(() =>
  indexes.value
    .map((index) => index.name)
    .filter((name) => indexEditorMode.value === 'add' || name !== indexEditorTarget.value?.name)
)

/**
 * Indexes the server owns (the unique username index on the users collection) are rebuilt on
 * every save, so offering to edit or delete them would only produce a change that reverts.
 */
function isManagedIndex(index: IndexDefinition): boolean {
  return index.fields.some((field) => isProtectedUserField(collection.value, field.field))
}

const columns = [
  { accessorKey: 'name', header: 'Field' },
  { accessorKey: 'type', header: 'Type' },
  { accessorKey: 'required', header: 'Required' },
  { accessorKey: 'indexEnabled', header: 'Indexed' },
  { accessorKey: 'indexDirection', header: 'Direction' },
  { accessorKey: 'indexUnique', header: 'Unique' },
  { id: 'actions', header: '' }
]

const indexColumns = [
  { accessorKey: 'name', header: 'Index' },
  { accessorKey: 'fields', header: 'Fields' },
  { accessorKey: 'unique', header: 'Unique' },
  { id: 'actions', header: '' }
]

// Reloading on a collection change as well as on mount: a deep link or the browser's
// back button can move straight from one collection's tab to another's, which reuses
// this component and would otherwise leave the previous collection on screen.
onMounted(loadDefinition)
watch(collection, loadDefinition)

async function loadDefinition() {
  loading.value = true
  try {
    definition.value = await api.getCollectionDefinition(collection.value)
    rows.value = buildRowsFromDefinition(definition.value)
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to load schema',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    loading.value = false
  }
}

function buildRowsFromDefinition(def: CollectionDefinition): SchemaRow[] {
  const singleIndexes = new Map<string, IndexDefinition>()
  for (const index of def.indexes || []) {
    if (index.fields.length === 1) {
      singleIndexes.set(index.fields[0].field, index)
    }
  }

  return (def.fields || []).map((field) => {
    const index = singleIndexes.get(field.name)
    const schemaOptions = optionsToSchemaRow(field)
    return {
      name: field.name,
      type: field.type,
      required: field.required,
      persisted: true,
      locked: isProtectedUserField(collection.value, field.name),
      indexEnabled: !!index,
      indexDirection: index?.fields[0]?.direction || 'ASC',
      indexUnique: index?.unique || false,
      ...schemaOptions,
      defaultValue: schemaOptions.defaultValue ?? ''
    }
  })
}

function openAddField() {
  openSchemaEditor({
    mode: 'add',
    row: emptyRow(),
    index: null,
    save: saveField
  })
}

function openEditField(_event: Event, tableRow: { index: number; original: SchemaRow }) {
  if (rows.value[tableRow.index]?.locked) return
  openSchemaEditor({
    mode: 'edit',
    row: rows.value[tableRow.index],
    index: tableRow.index,
    save: saveField,
    delete: requestDeleteField
  })
}

function requestDeleteField() {
  if (editorIndex.value === null) return
  fieldDeleteTarget.value = { index: editorIndex.value, name: editorRow.value.name }
  fieldDeleteOpen.value = true
}

function requestDeleteFieldFromRow(index: number) {
  if (rows.value[index]?.locked) return
  fieldDeleteTarget.value = { index, name: rows.value[index].name }
  fieldDeleteOpen.value = true
}

function applyEditorToRows(sourceRows: SchemaRow[]): SchemaRow[] {
  const draft = { ...editorRow.value, name: editorRow.value.name.trim() }
  if (!draft.name) {
    throw new Error('Field name is required')
  }
  if (isReservedSchemaFieldName(draft.name)) {
    throw new Error(`"${draft.name}" is a reserved system field name`)
  }
  if (isProtectedUserField(collection.value, draft.name)) {
    throw new Error(`"${draft.name}" is a protected field of the users collection`)
  }
  if (draft.type === 'RELATION' && !draft.relationCollection.trim()) {
    throw new Error(`Field "${draft.name}" requires a related collection`)
  }
  if (draft.type === 'FILE' && draft.fileMaxSelect < 1) {
    throw new Error(`Field "${draft.name}" requires maxSelect of at least 1`)
  }
  if (draft.type === 'SELECT') {
    const values = parseSelectValues(draft.selectValues)
    if (values.length === 0) {
      throw new Error(`Field "${draft.name}" requires at least one allowed value`)
    }
    if (draft.selectMaxSelect < 1) {
      throw new Error(`Field "${draft.name}" requires maxSelect of at least 1`)
    }
  }

  if (editorMode.value === 'edit') {
    if (editorIndex.value === null) {
      throw new Error('Missing field index')
    }
    const original = sourceRows[editorIndex.value]
    if (original?.persisted && original.name.trim() !== draft.name) {
      throw new Error('Existing field names cannot be changed')
    }
  }

  const duplicate = sourceRows.some((row, index) => {
    if (editorMode.value === 'edit' && index === editorIndex.value) return false
    return row.name.trim() === draft.name
  })
  if (duplicate) {
    throw new Error(`Field "${draft.name}" already exists`)
  }

  if (editorMode.value === 'add') {
    return [...sourceRows, { ...draft, persisted: false }]
  }

  return sourceRows.map((row, index) =>
    index === editorIndex.value ? { ...draft, persisted: row.persisted } : row
  )
}

async function persistSchema(nextRows: SchemaRow[], successMessage: string) {
  if (!definition.value) return
  setSaving(true)
  try {
    const next = buildDefinitionFromRows(nextRows)
    await api.updateCollectionDefinition(collection.value, definition.value.id, next)
    closeEditor()
    await loadDefinition()
    toast.add({ title: successMessage, color: 'success', icon: 'i-lucide-circle-check' })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to save schema',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    setSaving(false)
  }
}

async function saveField() {
  await persistSchema(applyEditorToRows(rows.value), editorMode.value === 'add' ? 'Field added' : 'Field saved')
}

async function confirmDeleteField() {
  if (!fieldDeleteTarget.value) return
  const { index } = fieldDeleteTarget.value
  fieldDeleteOpen.value = false
  fieldDeleteTarget.value = null
  const nextRows = rows.value.filter((_, rowIndex) => rowIndex !== index)
  await persistSchema(nextRows, 'Field deleted')
}

function buildDefinitionFromRows(
  sourceRows: SchemaRow[],
  overrideIndexes?: IndexDefinition[]
): CollectionDefinition {
  if (!definition.value) throw new Error('Missing definition')

  return {
    ...definition.value,
    fields: sourceRows.map((row) => schemaRowToField(row)),
    indexes: overrideIndexes ?? indexesForRows(sourceRows)
  }
}

/**
 * Rebuilds the index list for a change made in the field table. The per-field toggles only ever
 * describe one single-field index per field; everything else - compound indexes and any extra
 * index built in the index editor - is carried over untouched.
 */
function indexesForRows(sourceRows: SchemaRow[]): IndexDefinition[] {
  const existing = definition.value?.indexes || []
  const generated: IndexDefinition[] = []

  // Keep the name an existing single-field index already has instead of regenerating it: names
  // are part of the index identity server-side, so renaming one means dropping and rebuilding it.
  // Indexes created outside this editor (meta API, or server-owned ones like the unique username
  // index on users) do not follow the idx_<field> convention.
  const nameByField = new Map<string, string>()
  for (const index of existing) {
    if (index.fields.length === 1) {
      nameByField.set(index.fields[0].field, index.name)
    }
  }

  // Names the field table owns. Such an index is replaced by what its row says, or dropped when
  // the row's toggle is off - it must not be carried over as an untouched one.
  const ownedByRows = new Set<string>()

  for (const row of sourceRows) {
    const name = row.name.trim()
    if (!name) continue

    const existingName = nameByField.get(name)
    if (existingName) {
      ownedByRows.add(existingName)
    }

    if (row.indexEnabled) {
      const indexName = existingName ?? `idx_${name}`
      ownedByRows.add(indexName)
      generated.push({
        name: indexName,
        unique: row.indexUnique,
        fields: [{ field: name, direction: row.indexDirection }]
      })
    }
  }

  // A field that is gone takes every index mentioning it with it. An index pointing at a field
  // that no longer exists is rejected by the server for the whole definition, so a deleted field
  // that appears in a compound index would otherwise make the collection unsavable.
  const known = new Set(indexableFieldsFor(sourceRows))
  const preserved = existing.filter(
    (index) =>
      !ownedByRows.has(index.name) && index.fields.every((field) => known.has(field.field))
  )

  return [...generated, ...preserved]
}

function indexableFieldsFor(sourceRows: SchemaRow[]): string[] {
  return [...sourceRows.map((row) => row.name.trim()).filter(Boolean), ...SYSTEM_RECORD_FIELDS]
}

function openAddIndex() {
  indexEditorMode.value = 'add'
  indexEditorTarget.value = null
  indexEditorOpen.value = true
}

function openEditIndex(index: IndexDefinition) {
  if (isManagedIndex(index)) return
  indexEditorMode.value = 'edit'
  indexEditorTarget.value = index
  indexEditorOpen.value = true
}

function openEditIndexRow(_event: Event, tableRow: { original: IndexDefinition }) {
  openEditIndex(tableRow.original)
}

function requestDeleteIndexFromEditor() {
  const target = indexEditorTarget.value
  if (!target) return
  indexEditorOpen.value = false
  requestDeleteIndex(target)
}

async function saveIndex(edited: IndexDefinition) {
  const target = indexEditorTarget.value
  const next =
    indexEditorMode.value === 'add'
      ? [...indexes.value, edited]
      : indexes.value.map((index) => (index.name === target?.name ? edited : index))

  const saved = await persistIndexes(
    next,
    indexEditorMode.value === 'add' ? 'Index created' : 'Index saved'
  )
  if (saved) {
    indexEditorOpen.value = false
  }
}

function requestDeleteIndex(index: IndexDefinition) {
  if (isManagedIndex(index)) return
  indexDeleteTarget.value = index
  indexDeleteOpen.value = true
}

async function confirmDeleteIndex() {
  const target = indexDeleteTarget.value
  if (!target) return
  indexDeleteOpen.value = false
  indexDeleteTarget.value = null
  await persistIndexes(
    indexes.value.filter((index) => index.name !== target.name),
    'Index deleted'
  )
}

/**
 * Saves an index list as it is instead of deriving it from the field rows, so an index the field
 * table cannot express - a compound one, or a second index on the same field - survives.
 */
async function persistIndexes(
  nextIndexes: IndexDefinition[],
  successMessage: string
): Promise<boolean> {
  if (!definition.value) return false
  setSaving(true)
  try {
    const next = buildDefinitionFromRows(rows.value, nextIndexes)
    await api.updateCollectionDefinition(collection.value, definition.value.id, next)
    await loadDefinition()
    toast.add({ title: successMessage, color: 'success', icon: 'i-lucide-circle-check' })
    return true
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to save index',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
    return false
  } finally {
    setSaving(false)
  }
}

function describeIndexFields(index: IndexDefinition): string {
  return index.fields.map((field) => `${field.field} ${field.direction}`).join(', ')
}
</script>

<template>
  <div class="space-y-4">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <p class="text-sm text-muted">{{ rows.length }} fields</p>
      <div class="flex gap-2">
        <UButton icon="i-lucide-plus" @click="openAddField">Add field</UButton>
        <UButton
          v-if="!isSystem"
          color="error"
          variant="soft"
          icon="i-lucide-trash-2"
          @click="requestDeleteCollection(collection)"
        >
          Delete collection
        </UButton>
      </div>
    </div>

    <UAlert
      v-if="isSystem"
      color="info"
      variant="soft"
      icon="i-lucide-lock"
      title="System collection"
      description="Core fields are managed by Paprika and cannot be renamed or removed. You can still add custom fields, indexes, and rules."
    />

    <UCard :ui="{ body: 'p-0 sm:p-0' }">
      <UTable :data="rows" :columns="columns" :loading="loading" @select="openEditField">
        <template #name-cell="{ row }">
          <div class="flex items-center gap-1.5">
            <code>{{ row.original.name }}</code>
            <UIcon
              v-if="row.original.locked"
              name="i-lucide-lock"
              class="size-3.5 text-muted"
              aria-label="Managed field"
            />
          </div>
        </template>
        <template #type-cell="{ row }">
          <div class="flex flex-wrap items-center gap-2">
            <UBadge variant="soft" color="primary" class="gap-1">
              <UIcon :name="fieldTypeIcon(row.original.type)" class="size-3.5" />
              {{ fieldTypeLabel(row.original.type) }}
            </UBadge>
            <span
              v-if="row.original.type === 'RELATION' && row.original.relationCollection"
              class="font-mono text-xs text-muted"
            >
              → {{ row.original.relationCollection }}
            </span>
          </div>
        </template>
        <template #required-cell="{ row }">
          <UBadge :color="row.original.required ? 'success' : 'neutral'" variant="soft">
            {{ row.original.required ? 'yes' : 'no' }}
          </UBadge>
        </template>
        <template #indexEnabled-cell="{ row }">
          <UBadge :color="row.original.indexEnabled ? 'success' : 'neutral'" variant="soft">
            {{ row.original.indexEnabled ? 'yes' : 'no' }}
          </UBadge>
        </template>
        <template #indexDirection-cell="{ row }">
          <span v-if="row.original.indexEnabled" class="font-mono text-sm">
            {{ row.original.indexDirection }}
          </span>
          <span v-else class="text-muted">—</span>
        </template>
        <template #indexUnique-cell="{ row }">
          <UBadge
            v-if="row.original.indexEnabled"
            :color="row.original.indexUnique ? 'success' : 'neutral'"
            variant="soft"
          >
            {{ row.original.indexUnique ? 'yes' : 'no' }}
          </UBadge>
          <span v-else class="text-muted">—</span>
        </template>
        <template #actions-cell="{ row }">
          <div class="flex justify-end" @click.stop>
            <UButton
              v-if="!row.original.locked"
              size="sm"
              color="error"
              variant="soft"
              icon="i-lucide-trash-2"
              aria-label="Delete field"
              @click="requestDeleteFieldFromRow(row.index)"
            />
          </div>
        </template>
      </UTable>
    </UCard>

    <div class="flex flex-wrap items-center justify-between gap-3 pt-2">
      <div>
        <h2 class="font-semibold">Indexes</h2>
        <p class="text-sm text-muted">
          Every index of this collection, including compound ones. Single-field indexes can also
          be toggled on the field itself.
        </p>
      </div>
      <UButton icon="i-lucide-plus" variant="soft" @click="openAddIndex">Add index</UButton>
    </div>

    <UCard :ui="{ body: 'p-0 sm:p-0' }">
      <UTable
        :data="indexes"
        :columns="indexColumns"
        :loading="loading"
        @select="openEditIndexRow"
      >
        <template #name-cell="{ row }">
          <div class="flex items-center gap-1.5">
            <code>{{ row.original.name }}</code>
            <UIcon
              v-if="isManagedIndex(row.original)"
              name="i-lucide-lock"
              class="size-3.5 text-muted"
              aria-label="Managed index"
            />
            <UBadge v-if="row.original.fields.length > 1" variant="soft" color="primary">
              compound
            </UBadge>
          </div>
        </template>
        <template #fields-cell="{ row }">
          <span class="font-mono text-sm">{{ describeIndexFields(row.original) }}</span>
        </template>
        <template #unique-cell="{ row }">
          <UBadge :color="row.original.unique ? 'success' : 'neutral'" variant="soft">
            {{ row.original.unique ? 'yes' : 'no' }}
          </UBadge>
        </template>
        <template #actions-cell="{ row }">
          <div v-if="!isManagedIndex(row.original)" class="flex justify-end gap-2" @click.stop>
            <UButton
              size="sm"
              color="error"
              variant="soft"
              icon="i-lucide-trash-2"
              aria-label="Delete index"
              @click="requestDeleteIndex(row.original)"
            />
          </div>
        </template>
        <template #empty>
          <p class="py-6 text-center text-sm text-muted">No indexes on this collection yet.</p>
        </template>
      </UTable>
    </UCard>

    <IndexEditorSheet
      v-model:open="indexEditorOpen"
      :mode="indexEditorMode"
      :index="indexEditorTarget"
      :available-fields="indexableFields"
      :taken-names="takenIndexNames"
      :saving="saving"
      @save="saveIndex"
      @delete="requestDeleteIndexFromEditor"
    />

    <UModal v-model:open="indexDeleteOpen" portal="body" :ui="modalUi">
      <template #content>
        <UCard>
          <template #header>
            <div class="flex items-center gap-2">
              <UIcon name="i-lucide-triangle-alert" class="size-5 text-error" />
              <h3 class="font-semibold">Delete index</h3>
            </div>
          </template>
          <p class="text-sm text-muted">
            Delete index "{{ indexDeleteTarget?.name }}"? Queries relying on it get slower, no data
            is removed.
          </p>
          <template #footer>
            <div class="flex justify-end gap-2">
              <UButton variant="ghost" color="neutral" @click="indexDeleteOpen = false">Cancel</UButton>
              <UButton color="error" :loading="saving" icon="i-lucide-trash-2" @click="confirmDeleteIndex">
                Delete index
              </UButton>
            </div>
          </template>
        </UCard>
      </template>
    </UModal>

    <UModal v-model:open="fieldDeleteOpen" portal="body" :ui="modalUi">
      <template #content>
        <UCard>
          <template #header>
            <div class="flex items-center gap-2">
              <UIcon name="i-lucide-triangle-alert" class="size-5 text-error" />
              <h3 class="font-semibold">Delete field</h3>
            </div>
          </template>
          <p class="text-sm text-muted">
            Delete field "{{ fieldDeleteTarget?.name }}" from this collection? Existing data in this column
            is not removed automatically.
          </p>
          <template #footer>
            <div class="flex justify-end gap-2">
              <UButton variant="ghost" color="neutral" @click="fieldDeleteOpen = false">Cancel</UButton>
              <UButton color="error" :loading="saving" icon="i-lucide-trash-2" @click="confirmDeleteField">
                Delete field
              </UButton>
            </div>
          </template>
        </UCard>
      </template>
    </UModal>
  </div>
</template>
