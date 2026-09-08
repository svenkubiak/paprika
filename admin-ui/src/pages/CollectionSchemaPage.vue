<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { type SchemaRow } from '@/components/SchemaEditorSheet.vue'
import { api } from '@/lib/api'
import { useSchemaEditorSheet, emptyRow } from '@/composables/useSchemaEditorSheet'
import { useAppToast } from '@/composables/useAppToast'
import { modalUi } from '@/lib/overlay-ui'
import { fieldTypeIcon, fieldTypeLabel } from '@/lib/utils'
import { isReservedSchemaFieldName } from '@/lib/system-fields'
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

const columns = [
  { accessorKey: 'name', header: 'Field' },
  { accessorKey: 'type', header: 'Type' },
  { accessorKey: 'required', header: 'Required' },
  { accessorKey: 'indexEnabled', header: 'Indexed' },
  { accessorKey: 'indexDirection', header: 'Direction' },
  { accessorKey: 'indexUnique', header: 'Unique' },
  { id: 'actions', header: '' }
]

onMounted(loadDefinition)

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

function buildDefinitionFromRows(sourceRows: SchemaRow[]): CollectionDefinition {
  if (!definition.value) throw new Error('Missing definition')

  const fields = sourceRows.map((row) => schemaRowToField(row))
  const indexes: IndexDefinition[] = []
  const compoundIndexes = (definition.value.indexes || []).filter((index) => index.fields.length > 1)

  for (const row of sourceRows) {
    const name = row.name.trim()
    if (!name) continue
    if (row.indexEnabled) {
      indexes.push({
        name: `idx_${name}`,
        unique: row.indexUnique,
        fields: [{ field: name, direction: row.indexDirection }]
      })
    }
  }

  return {
    ...definition.value,
    fields,
    indexes: [...indexes, ...compoundIndexes]
  }
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
