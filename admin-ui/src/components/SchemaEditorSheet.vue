<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import OverlayDrawer from '@/components/OverlayDrawer.vue'
import PField from '@/components/PField.vue'
import PSwitchField from '@/components/PSwitchField.vue'
import SegmentedControl from '@/components/SegmentedControl.vue'
import { useBootstrap } from '@/composables/useBootstrap'
import type { SchemaEditorMode } from '@/composables/useSchemaEditorSheet'
import { schemaFieldHints } from '@/lib/schema-field-hints'
import { DEFAULT_FILE_MAX_SIZE } from '@/lib/schema-options'
import {
  formatByteSize,
  fieldTypeChoice,
  fieldTypeChoiceIcon,
  fieldTypeChoiceMultiline,
  fieldTypeChoiceSelectItems,
  fieldTypeChoiceType,
  SELECT_EMPTY,
  type FieldTypeChoice
} from '@/lib/utils'
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
  fileImageWidths: string
  selectValues: string
  selectMaxSelect: number
  minLength?: number
  maxLength?: number
  pattern: string
  /** STRING only: selects the "Text" entry, i.e. options.multiline. */
  stringMultiline: boolean
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

const fieldTypeItems = fieldTypeChoiceSelectItems()

/** "String" and "Text" share the STRING type; switching only flips options.multiline. */
const typeChoice = computed<FieldTypeChoice>({
  get: () => fieldTypeChoice(props.row.type, props.row.stringMultiline),
  set: (choice) => {
    props.row.type = fieldTypeChoiceType(choice)
    props.row.stringMultiline = fieldTypeChoiceMultiline(choice)
  }
})
const directionItems = [
  { label: 'Ascending', value: 'ASC' },
  { label: 'Descending', value: 'DESC' }
]
const booleanDefaultItems = [
  { label: 'true', value: 'true' },
  { label: 'false', value: 'false' },
  { label: 'No default', value: SELECT_EMPTY }
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
    props.row.fileMaxSize = DEFAULT_FILE_MAX_SIZE
    props.row.fileMimeTypes = ''
    props.row.fileMaxSelect = 1
    props.row.fileImageWidths = ''
  }
  if (props.row.type !== 'SELECT') {
    props.row.selectValues = ''
    props.row.selectMaxSelect = 1
  }
}

function typeIcon(type: FieldDefinition['type'], multiline: boolean) {
  return fieldTypeChoiceIcon(type, multiline)
}

const patternSample = ref('')

const patternError = computed(() => {
  const pattern = props.row.pattern.trim()
  if (!pattern) return undefined
  try {
    new RegExp(pattern)
    return undefined
  } catch (error) {
    return `Not a valid regular expression: ${error instanceof Error ? error.message : 'unknown error'}`
  }
})

const patternSampleMatches = computed(() => {
  const pattern = props.row.pattern.trim()
  if (!pattern || patternError.value || !patternSample.value) return undefined
  try {
    return new RegExp(pattern).test(patternSample.value)
  } catch {
    return undefined
  }
})

const fileMaxSizeHint = computed(() => {
  const readable = formatByteSize(props.row.fileMaxSize)
  return readable ? `About ${readable} per file` : ''
})

const imageWidthCount = computed(
  () => props.row.fileImageWidths.split(/[\n,\s]+/).filter(Boolean).length
)

const jsonMaxBytesHint = computed(() => {
  const readable = formatByteSize(props.row.jsonMaxBytes)
  return readable ? `About ${readable}` : ''
})
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
          <PField
            label="Name"
            icon="i-lucide-key"
            :help="schemaFieldHints.name.short"
            :details="schemaFieldHints.name.long"
          >
            <UInput
              v-model="row.name"
              class="font-mono"
              placeholder="e.g. title"
              :disabled="mode === 'edit'"
              autofocus
            />
          </PField>

          <PField
            label="Type"
            icon="i-lucide-shapes"
            :help="schemaFieldHints.type.short"
            :details="schemaFieldHints.type.long"
          >
            <USelect
              v-model="typeChoice"
              :items="fieldTypeItems"
              :icon="typeIcon(row.type, row.stringMultiline)"
              :content="selectContentProps"
              :ui="selectMenuUi"
              @update:model-value="onTypeChange"
            />
          </PField>

          <PField
            v-if="row.type === 'RELATION'"
            label="Related collection"
            icon="i-lucide-database"
            :help="schemaFieldHints.relationCollection.short"
            :details="schemaFieldHints.relationCollection.long"
          >
            <USelect
              v-model="row.relationCollection"
              :items="relationCollectionItems"
              placeholder="Select collection"
              icon="i-lucide-database"
              :content="selectContentProps"
              :ui="selectMenuUi"
              class="font-mono"
            />
          </PField>

          <template v-if="row.type === 'FILE'">
            <PField
              label="Max file size"
              icon="i-lucide-paperclip"
              width="md"
              :help="schemaFieldHints.fileMaxSize.short"
              :details="schemaFieldHints.fileMaxSize.long"
              :hint="fileMaxSizeHint"
            >
              <UInput v-model.number="row.fileMaxSize" type="number" class="font-mono">
                <template #trailing>
                  <span class="text-xs text-muted">bytes</span>
                </template>
              </UInput>
            </PField>
            <PField
              label="Allowed MIME types"
              icon="i-lucide-file-text"
              :help="schemaFieldHints.fileMimeTypes.short"
              :details="schemaFieldHints.fileMimeTypes.long"
            >
              <UInput v-model="row.fileMimeTypes" class="font-mono" placeholder="image/jpeg, image/png" />
            </PField>
            <PField
              label="Max files"
              icon="i-lucide-hash"
              width="sm"
              :help="schemaFieldHints.fileMaxSelect.short"
            >
              <UInput v-model.number="row.fileMaxSelect" type="number" min="1" class="font-mono" />
            </PField>
            <PField
              label="Image widths"
              icon="i-lucide-sliders-horizontal"
              width="md"
              :help="schemaFieldHints.fileImageWidths.short"
              :details="schemaFieldHints.fileImageWidths.long"
              :counter="`${imageWidthCount} / 4`"
              :counter-exceeded="imageWidthCount > 4"
            >
              <UInput v-model="row.fileImageWidths" class="font-mono" placeholder="320, 800">
                <template #trailing>
                  <span class="text-xs text-muted">px</span>
                </template>
              </UInput>
            </PField>
          </template>

          <template v-if="row.type === 'SELECT'">
            <PField
              label="Allowed values"
              icon="i-lucide-list"
              :help="schemaFieldHints.selectValues.short"
            >
              <UTextarea v-model="row.selectValues" :rows="5" class="font-mono" />
            </PField>
            <PField
              label="Max selections"
              icon="i-lucide-hash"
              width="sm"
              :help="schemaFieldHints.selectMaxSelect.short"
            >
              <UInput v-model.number="row.selectMaxSelect" type="number" min="1" class="font-mono" />
            </PField>
          </template>

          <template v-if="row.type === 'RELATION'">
            <PField
              label="Max relations"
              icon="i-lucide-hash"
              width="sm"
              :help="schemaFieldHints.relationMaxSelect.short"
            >
              <UInput v-model.number="row.relationMaxSelect" type="number" min="1" class="font-mono" />
            </PField>
            <PSwitchField
              v-model="row.relationCascadeDelete"
              label="Cascade delete related records"
              icon="i-lucide-trash-2"
              :help="schemaFieldHints.relationCascadeDelete.short"
              :details="schemaFieldHints.relationCascadeDelete.long"
            />
          </template>

          <template v-if="row.type === 'STRING' || row.type === 'EMAIL' || row.type === 'URL'">
            <div class="grid gap-3 sm:grid-cols-2">
              <PField label="Min length" icon="i-lucide-ruler" :help="schemaFieldHints.minLength.short">
                <UInput v-model.number="row.minLength" type="number" min="0" class="font-mono" />
              </PField>
              <PField label="Max length" icon="i-lucide-ruler" :help="schemaFieldHints.maxLength.short">
                <UInput v-model.number="row.maxLength" type="number" min="1" class="font-mono" />
              </PField>
            </div>
            <PField
              label="Pattern"
              icon="i-lucide-regex"
              :help="schemaFieldHints.pattern.short"
              :details="schemaFieldHints.pattern.long"
              :error="patternError"
            >
              <UInput v-model="row.pattern" class="font-mono" placeholder="^[a-z-]+$" />
            </PField>
            <PField
              v-if="row.pattern.trim() && !patternError"
              label="Sample value"
              icon="i-lucide-flask-conical"
              help="Checked against the pattern above. Not stored."
              :hint="
                patternSampleMatches === undefined
                  ? ''
                  : patternSampleMatches
                    ? 'The sample matches.'
                    : 'The sample does not match.'
              "
            >
              <UInput v-model="patternSample" class="font-mono" placeholder="Try a value">
                <template v-if="patternSampleMatches !== undefined" #trailing>
                  <UIcon
                    :name="patternSampleMatches ? 'i-lucide-circle-check' : 'i-lucide-circle-x'"
                    class="size-4"
                    :class="patternSampleMatches ? 'text-readable-success' : 'text-readable-error'"
                  />
                </template>
              </UInput>
            </PField>
          </template>

          <template v-if="row.type === 'NUMBER'">
            <div class="grid gap-3 sm:grid-cols-2">
              <PField label="Minimum" icon="i-lucide-hash" :help="schemaFieldHints.numberMin.short">
                <UInput v-model.number="row.numberMin" type="number" step="any" class="font-mono" />
              </PField>
              <PField label="Maximum" icon="i-lucide-hash" :help="schemaFieldHints.numberMax.short">
                <UInput v-model.number="row.numberMax" type="number" step="any" class="font-mono" />
              </PField>
            </div>
          </template>

          <template v-if="row.type === 'JSON'">
            <div class="grid gap-3 sm:grid-cols-2">
              <PField
                label="Max bytes"
                icon="i-lucide-ruler"
                :help="schemaFieldHints.jsonMaxBytes.short"
                :hint="jsonMaxBytesHint"
              >
                <UInput v-model.number="row.jsonMaxBytes" type="number" min="1" class="font-mono">
                  <template #trailing>
                    <span class="text-xs text-muted">bytes</span>
                  </template>
                </UInput>
              </PField>
              <PField
                label="Max depth"
                icon="i-lucide-layers"
                :help="schemaFieldHints.jsonMaxDepth.short"
                :details="schemaFieldHints.jsonMaxDepth.long"
              >
                <UInput v-model.number="row.jsonMaxDepth" type="number" min="1" class="font-mono" />
              </PField>
            </div>
            <PSwitchField
              v-model="row.jsonOnlyObject"
              label="Only JSON objects"
              icon="i-lucide-braces"
              :help="schemaFieldHints.jsonOnlyObject.short"
            />
            <PSwitchField
              v-model="row.jsonOnlyArray"
              label="Only JSON arrays"
              icon="i-lucide-braces"
              :help="schemaFieldHints.jsonOnlyArray.short"
            />
          </template>

          <template v-if="row.type === 'DATE'">
            <div class="grid gap-3 sm:grid-cols-2">
              <PField label="Min date" icon="i-lucide-calendar" :help="schemaFieldHints.minDate.short">
                <UInput v-model="row.minDate" type="date" class="font-mono" />
              </PField>
              <PField label="Max date" icon="i-lucide-calendar" :help="schemaFieldHints.maxDate.short">
                <UInput v-model="row.maxDate" type="date" class="font-mono" />
              </PField>
            </div>
          </template>

          <template v-if="row.type === 'TIME'">
            <div class="grid gap-3 sm:grid-cols-2">
              <PField label="Min time" icon="i-lucide-clock" :help="schemaFieldHints.minTime.short">
                <UInput v-model="row.minTime" type="time" class="font-mono" />
              </PField>
              <PField label="Max time" icon="i-lucide-clock" :help="schemaFieldHints.maxTime.short">
                <UInput v-model="row.maxTime" type="time" class="font-mono" />
              </PField>
            </div>
          </template>

          <template v-if="row.type === 'DATETIME'">
            <PField
              label="Min datetime"
              icon="i-lucide-calendar-clock"
              :help="schemaFieldHints.minDateTime.short"
              :details="schemaFieldHints.minDateTime.long"
              hint="ISO 8601 with timezone"
            >
              <UInput v-model="row.minDateTime" class="font-mono" placeholder="2026-01-01T00:00:00Z" />
            </PField>
            <PField
              label="Max datetime"
              icon="i-lucide-calendar-clock"
              :help="schemaFieldHints.maxDateTime.short"
              :details="schemaFieldHints.maxDateTime.long"
              hint="ISO 8601 with timezone"
            >
              <UInput v-model="row.maxDateTime" class="font-mono" placeholder="2026-12-31T23:59:59Z" />
            </PField>
          </template>

          <PField
            v-if="supportsDefaultValue"
            label="Default value"
            icon="i-lucide-wand-sparkles"
            :width="row.type === 'BOOLEAN' ? 'full' : 'md'"
            :help="row.type === 'BOOLEAN' ? schemaFieldHints.defaultBoolean.short : schemaFieldHints.defaultValue.short"
            :details="row.type === 'BOOLEAN' ? schemaFieldHints.defaultBoolean.long : schemaFieldHints.defaultValue.long"
          >
            <SegmentedControl
              v-if="row.type === 'BOOLEAN'"
              v-model="booleanDefaultValue"
              :items="booleanDefaultItems"
              :muted-value="SELECT_EMPTY"
              aria-label="Default value"
            />
            <UInput v-else v-model="row.defaultValue" class="font-mono" />
          </PField>

          <div class="space-y-3 border-t border-default pt-4">
            <PSwitchField
              v-model="row.required"
              label="Required field"
              icon="i-lucide-asterisk"
              :help="schemaFieldHints.required.short"
            />
            <PSwitchField
              v-model="row.indexEnabled"
              label="Create index"
              icon="i-lucide-zap"
              :disabled="!row.name.trim()"
              :help="schemaFieldHints.indexEnabled.short"
            />
          </div>

          <div
            v-if="row.indexEnabled"
            class="space-y-3 rounded-lg border border-default bg-muted/20 p-3"
          >
            <p class="text-xs font-medium uppercase tracking-wide text-muted">Index options</p>
            <PField
              label="Sort direction"
              icon="i-lucide-arrow-up-down"
              width="md"
              :help="schemaFieldHints.indexDirection.short"
            >
              <USelect
                v-model="row.indexDirection"
                :items="directionItems"
                :content="selectContentProps"
                :ui="selectMenuUi"
              />
            </PField>
            <PSwitchField
              v-model="row.indexUnique"
              label="Unique index"
              icon="i-lucide-fingerprint"
              :help="schemaFieldHints.indexUnique.short"
            />
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
