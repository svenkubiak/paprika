<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import OverlayDrawer from '@/components/OverlayDrawer.vue'
import FieldLabelHelp from '@/components/FieldLabelHelp.vue'
import { useBootstrap } from '@/composables/useBootstrap'
import type { SchemaEditorMode } from '@/composables/useSchemaEditorSheet'
import { schemaFieldHints } from '@/lib/schema-field-hints'
import { fieldTypeIcon, fieldTypeSelectItems, SELECT_EMPTY } from '@/lib/utils'
import { selectContentProps, selectMenuUi } from '@/lib/overlay-ui'
import type { FieldDefinition } from '@/types'

export interface SchemaRow {
  name: string
  type: FieldDefinition['type']
  required: boolean
  persisted: boolean
  locked?: boolean
  indexEnabled: boolean
  indexDirection: 'ASC' | 'DESC'
  indexUnique: boolean
  defaultValue: string
  relationCollection: string
  relationMaxSelect: number
  relationCascadeDelete: boolean
  fileMaxSize: number
  fileMimeTypes: string
  fileMaxSelect: number
  selectValues: string
  selectMaxSelect: number
  minLength?: number
  maxLength?: number
  pattern: string
  numberMin?: number
  numberMax?: number
  jsonMaxBytes?: number
  jsonMaxDepth?: number
  jsonOnlyObject: boolean
  jsonOnlyArray: boolean
  minDate: string
  maxDate: string
  minTime: string
  maxTime: string
  minDateTime: string
  maxDateTime: string
}

const props = defineProps<{
  open: boolean
  row: SchemaRow
  mode: SchemaEditorMode
  saving?: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  save: []
  delete: []
}>()

const route = useRoute()
const { bootstrap } = useBootstrap()

const fieldTypeItems = fieldTypeSelectItems()
const directionItems = [
  { label: 'Ascending', value: 'ASC' },
  { label: 'Descending', value: 'DESC' }
]
const booleanDefaultItems = [
  { label: 'No default', value: SELECT_EMPTY },
  { label: 'true', value: 'true' },
  { label: 'false', value: 'false' }
]

const booleanDefaultValue = computed({
  get: () => props.row.defaultValue || SELECT_EMPTY,
  set: (value: string) => {
    props.row.defaultValue = value === SELECT_EMPTY ? '' : value
  }
})

watch(
  () => props.row.type,
  (_type, previousType) => {
    if (previousType === undefined) return
    props.row.defaultValue = ''
  }
)

const supportsDefaultValue = computed(
  () => props.row.type !== 'FILE' && props.row.type !== 'RELATION'
)

const currentCollection = computed(() => String(route.params.collection || ''))

const relationCollectionItems = computed(() => {
  const relationTargets = bootstrap.value?.relationCollections ?? []
  const visible = bootstrap.value?.collections ?? []
  const names = [...new Set([...relationTargets, ...visible])].filter(
    (name) => name !== currentCollection.value
  )

  return names.map((name) => ({
    label:
      name === 'users'
        ? `users (${bootstrap.value?.activeTenant?.name ?? 'current tenant'} only)`
        : name,
    value: name,
    icon: name === 'users' ? 'i-lucide-users' : 'i-lucide-database'
  }))
})

const title = computed(() =>
  props.mode === 'add' ? 'Add field' : `Edit field${props.row.name ? `: ${props.row.name}` : ''}`
)

const description = computed(() =>
  props.mode === 'add'
    ? 'Define a new field for this collection.'
    : 'Update this field and its index settings. Existing field names cannot be changed.'
)

function onTypeChange() {
  if (props.row.type !== 'RELATION') {
    props.row.relationCollection = ''
  }
  if (props.row.type !== 'FILE') {
    props.row.fileMaxSize = 5 * 1024 * 1024
    props.row.fileMimeTypes = ''
    props.row.fileMaxSelect = 1
  }
  if (props.row.type !== 'SELECT') {
    props.row.selectValues = ''
    props.row.selectMaxSelect = 1
  }
}

function typeIcon(type: FieldDefinition['type']) {
  return fieldTypeIcon(type)
}
</script>

<template>
  <OverlayDrawer
    :open="open"
    side="right"
    width-class="w-full max-w-xl"
    labelled-by="schema-editor-title"
    @update:open="emit('update:open', $event)"
  >
    <template #default="{ close }">
      <div class="flex items-start justify-between gap-3 border-b border-default p-4 sm:px-6">
        <div class="min-w-0">
          <h2 id="schema-editor-title" class="font-semibold">{{ title }}</h2>
          <p class="mt-1 text-sm text-muted">{{ description }}</p>
        </div>
        <UButton
          icon="i-lucide-x"
          variant="ghost"
          color="neutral"
          aria-label="Close schema editor"
          @click="close"
        />
      </div>

      <div class="flex-1 overflow-y-auto p-4 sm:p-6">
        <UCard variant="subtle" :ui="{ body: 'space-y-4 p-4 sm:p-4' }">
          <UFormField required class="w-full">
            <template #label>
              <FieldLabelHelp label="Name" :hint="schemaFieldHints.name" />
            </template>
            <UInput
              v-model="row.name"
              class="w-full font-mono"
              placeholder="e.g. title"
              icon="i-lucide-tag"
              :disabled="mode === 'edit'"
              autofocus
            />
          </UFormField>

          <UFormField required class="w-full">
            <template #label>
              <FieldLabelHelp label="Type" :hint="schemaFieldHints.type" />
            </template>
            <USelect
              v-model="row.type"
              :items="fieldTypeItems"
              :icon="typeIcon(row.type)"
              :content="selectContentProps"
              :ui="selectMenuUi"
              class="w-full"
              @update:model-value="onTypeChange"
            />
          </UFormField>

          <UFormField v-if="row.type === 'RELATION'" required class="w-full">
            <template #label>
              <FieldLabelHelp label="Related collection" :hint="schemaFieldHints.relationCollection" />
            </template>
            <USelect
              v-model="row.relationCollection"
              :items="relationCollectionItems"
              placeholder="Select collection"
              icon="i-lucide-database"
              :content="selectContentProps"
              :ui="selectMenuUi"
              class="w-full font-mono"
            />
          </UFormField>

          <template v-if="row.type === 'FILE'">
            <UFormField class="w-full">
              <template #label>
                <FieldLabelHelp label="Max file size (bytes)" :hint="schemaFieldHints.fileMaxSize" />
              </template>
              <UInput v-model.number="row.fileMaxSize" type="number" class="w-full font-mono" />
            </UFormField>
            <UFormField class="w-full">
              <template #label>
                <FieldLabelHelp label="Allowed MIME types" :hint="schemaFieldHints.fileMimeTypes" />
              </template>
              <UInput v-model="row.fileMimeTypes" class="w-full font-mono" />
            </UFormField>
            <UFormField class="w-full">
              <template #label>
                <FieldLabelHelp label="Max files" :hint="schemaFieldHints.fileMaxSelect" />
              </template>
              <UInput v-model.number="row.fileMaxSelect" type="number" min="1" class="w-full font-mono" />
            </UFormField>
          </template>

          <template v-if="row.type === 'SELECT'">
            <UFormField required class="w-full">
              <template #label>
                <FieldLabelHelp label="Allowed values" :hint="schemaFieldHints.selectValues" />
              </template>
              <UTextarea v-model="row.selectValues" :rows="5" class="w-full font-mono" />
            </UFormField>
            <UFormField class="w-full">
              <template #label>
                <FieldLabelHelp label="Max selections" :hint="schemaFieldHints.selectMaxSelect" />
              </template>
              <UInput v-model.number="row.selectMaxSelect" type="number" min="1" class="w-full font-mono" />
            </UFormField>
          </template>

          <template v-if="row.type === 'RELATION'">
            <UFormField class="w-full">
              <template #label>
                <FieldLabelHelp label="Max relations" :hint="schemaFieldHints.relationMaxSelect" />
              </template>
              <UInput v-model.number="row.relationMaxSelect" type="number" min="1" class="w-full font-mono" />
            </UFormField>
            <div class="flex items-center justify-between gap-3">
              <FieldLabelHelp
                label="Cascade delete related records"
                :hint="schemaFieldHints.relationCascadeDelete"
              />
              <USwitch v-model="row.relationCascadeDelete" />
            </div>
          </template>

          <template v-if="row.type === 'STRING' || row.type === 'EMAIL' || row.type === 'URL'">
            <div class="grid gap-3 sm:grid-cols-2">
              <UFormField class="w-full">
                <template #label>
                  <FieldLabelHelp label="Min length" :hint="schemaFieldHints.minLength" />
                </template>
                <UInput v-model.number="row.minLength" type="number" min="0" class="w-full font-mono" />
              </UFormField>
              <UFormField class="w-full">
                <template #label>
                  <FieldLabelHelp label="Max length" :hint="schemaFieldHints.maxLength" />
                </template>
                <UInput v-model.number="row.maxLength" type="number" min="1" class="w-full font-mono" />
              </UFormField>
            </div>
            <UFormField class="w-full">
              <template #label>
                <FieldLabelHelp label="Pattern (regex)" :hint="schemaFieldHints.pattern" />
              </template>
              <UInput v-model="row.pattern" class="w-full font-mono" placeholder="^[a-z-]+$" />
            </UFormField>
          </template>

          <template v-if="row.type === 'NUMBER'">
            <div class="grid gap-3 sm:grid-cols-2">
              <UFormField class="w-full">
                <template #label>
                  <FieldLabelHelp label="Minimum" :hint="schemaFieldHints.numberMin" />
                </template>
                <UInput v-model.number="row.numberMin" type="number" step="any" class="w-full font-mono" />
              </UFormField>
              <UFormField class="w-full">
                <template #label>
                  <FieldLabelHelp label="Maximum" :hint="schemaFieldHints.numberMax" />
                </template>
                <UInput v-model.number="row.numberMax" type="number" step="any" class="w-full font-mono" />
              </UFormField>
            </div>
          </template>

          <template v-if="row.type === 'JSON'">
            <div class="grid gap-3 sm:grid-cols-2">
              <UFormField class="w-full">
                <template #label>
                  <FieldLabelHelp label="Max bytes" :hint="schemaFieldHints.jsonMaxBytes" />
                </template>
                <UInput v-model.number="row.jsonMaxBytes" type="number" min="1" class="w-full font-mono" />
              </UFormField>
              <UFormField class="w-full">
                <template #label>
                  <FieldLabelHelp label="Max depth" :hint="schemaFieldHints.jsonMaxDepth" />
                </template>
                <UInput v-model.number="row.jsonMaxDepth" type="number" min="1" class="w-full font-mono" />
              </UFormField>
            </div>
            <div class="flex items-center justify-between gap-3">
              <FieldLabelHelp label="Only JSON objects" :hint="schemaFieldHints.jsonOnlyObject" />
              <USwitch v-model="row.jsonOnlyObject" />
            </div>
            <div class="flex items-center justify-between gap-3">
              <FieldLabelHelp label="Only JSON arrays" :hint="schemaFieldHints.jsonOnlyArray" />
              <USwitch v-model="row.jsonOnlyArray" />
            </div>
          </template>

          <template v-if="row.type === 'DATE'">
            <div class="grid gap-3 sm:grid-cols-2">
              <UFormField class="w-full">
                <template #label>
                  <FieldLabelHelp label="Min date" :hint="schemaFieldHints.minDate" />
                </template>
                <UInput v-model="row.minDate" type="date" class="w-full font-mono" />
              </UFormField>
              <UFormField class="w-full">
                <template #label>
                  <FieldLabelHelp label="Max date" :hint="schemaFieldHints.maxDate" />
                </template>
                <UInput v-model="row.maxDate" type="date" class="w-full font-mono" />
              </UFormField>
            </div>
          </template>

          <template v-if="row.type === 'TIME'">
            <div class="grid gap-3 sm:grid-cols-2">
              <UFormField class="w-full">
                <template #label>
                  <FieldLabelHelp label="Min time" :hint="schemaFieldHints.minTime" />
                </template>
                <UInput v-model="row.minTime" type="time" class="w-full font-mono" />
              </UFormField>
              <UFormField class="w-full">
                <template #label>
                  <FieldLabelHelp label="Max time" :hint="schemaFieldHints.maxTime" />
                </template>
                <UInput v-model="row.maxTime" type="time" class="w-full font-mono" />
              </UFormField>
            </div>
          </template>

          <template v-if="row.type === 'DATETIME'">
            <UFormField class="w-full">
              <template #label>
                <FieldLabelHelp label="Min datetime (ISO)" :hint="schemaFieldHints.minDateTime" />
              </template>
              <UInput v-model="row.minDateTime" class="w-full font-mono" placeholder="2026-01-01T00:00:00Z" />
            </UFormField>
            <UFormField class="w-full">
              <template #label>
                <FieldLabelHelp label="Max datetime (ISO)" :hint="schemaFieldHints.maxDateTime" />
              </template>
              <UInput v-model="row.maxDateTime" class="w-full font-mono" placeholder="2026-12-31T23:59:59Z" />
            </UFormField>
          </template>

          <UFormField v-if="supportsDefaultValue" class="w-full">
            <template #label>
              <FieldLabelHelp
                label="Default value"
                :hint="row.type === 'BOOLEAN' ? schemaFieldHints.defaultBoolean : schemaFieldHints.defaultValue"
              />
            </template>
            <USelect
              v-if="row.type === 'BOOLEAN'"
              v-model="booleanDefaultValue"
              :items="booleanDefaultItems"
              :content="selectContentProps"
              :ui="selectMenuUi"
              class="w-full"
            />
            <UInput v-else v-model="row.defaultValue" class="w-full font-mono" />
          </UFormField>

          <div class="space-y-3 border-t border-default pt-4">
            <div class="flex items-center justify-between gap-3">
              <FieldLabelHelp label="Required field" :hint="schemaFieldHints.required" />
              <USwitch v-model="row.required" />
            </div>
            <div class="flex items-center justify-between gap-3">
              <FieldLabelHelp label="Create index" :hint="schemaFieldHints.indexEnabled" />
              <USwitch v-model="row.indexEnabled" :disabled="!row.name.trim()" />
            </div>
          </div>

          <div
            v-if="row.indexEnabled"
            class="space-y-3 rounded-lg border border-default bg-muted/20 p-3"
          >
            <p class="text-xs font-medium uppercase tracking-wide text-muted">Index options</p>
            <UFormField class="w-full">
              <template #label>
                <FieldLabelHelp label="Sort direction" :hint="schemaFieldHints.indexDirection" />
              </template>
              <USelect
                v-model="row.indexDirection"
                :items="directionItems"
                :content="selectContentProps"
                :ui="selectMenuUi"
                class="w-full"
              />
            </UFormField>
            <div class="flex items-center justify-between gap-3">
              <FieldLabelHelp label="Unique index" :hint="schemaFieldHints.indexUnique" />
              <USwitch v-model="row.indexUnique" />
            </div>
          </div>
        </UCard>
      </div>

      <div class="flex flex-col gap-2 border-t border-default p-4 sm:flex-row sm:px-6">
        <UButton class="flex-1" :loading="saving" icon="i-lucide-save" @click="emit('save')">
          Save field
        </UButton>
        <UButton
          v-if="mode === 'edit' && row.persisted"
          color="error"
          variant="soft"
          icon="i-lucide-trash-2"
          :loading="saving"
          @click="emit('delete')"
        >
          Delete
        </UButton>
        <UButton variant="ghost" color="neutral" @click="close">Cancel</UButton>
      </div>
    </template>
  </OverlayDrawer>
</template>
