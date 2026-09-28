<script setup lang="ts">
import { computed, nextTick, ref, useTemplateRef, watch } from 'vue'
import type { FieldDefinition } from '@/types'
import CopyButton from '@/components/CopyButton.vue'
import FieldValueInput from '@/components/FieldValueInput.vue'
import PField from '@/components/PField.vue'
import SegmentedControl from '@/components/SegmentedControl.vue'
import { slideoverUi } from '@/lib/overlay-ui'
import { fieldDisplayName, fieldTypeIcon, formatByteSize } from '@/lib/utils'
import { fieldConstraintHint, fieldCounter } from '@/lib/field-constraints'
import { validateFieldValue, validateRecordValues } from '@/lib/field-validation'
import { SYSTEM_RECORD_FIELDS } from '@/lib/system-fields'
import {
  buildRecordFormState,
  serializeRecordForm,
  type RecordFormState
} from '@/lib/record-form'

export type RecordSavePayload = {
  values: Record<string, unknown>
  files: Record<string, File[]>
}

const props = defineProps<{
  open: boolean
  mode: 'new' | 'edit'
  record: Record<string, unknown>
  fields: FieldDefinition[]
  saving?: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  save: [payload: RecordSavePayload]
  delete: []
  'validation-error': [message: string]
}>()

const view = ref<'form' | 'json'>('form')
const form = ref<RecordFormState>({ values: {}, jsonText: {}, dateTimeInitial: {} })
const jsonState = ref('')
const fileSelections = ref<Record<string, File[]>>({})

/** Validation messages per field name, filled on save and cleared as each field is corrected. */
const errors = ref<Record<string, string>>({})
const body = useTemplateRef<HTMLElement>('body')

const viewItems = [
  { label: 'Form', value: 'form' },
  { label: 'JSON', value: 'json' }
]

watch(
  () => [props.record, props.fields] as const,
  ([record, fields]) => {
    form.value = buildRecordFormState(record, fields, props.mode)
    fileSelections.value = {}
    errors.value = {}

    const payload = { ...record }
    for (const name of [...SYSTEM_RECORD_FIELDS, 'created', 'updated']) {
      delete payload[name]
    }
    jsonState.value = JSON.stringify(payload, null, 2)
  },
  { immediate: true, deep: true }
)

const title = computed(() => (props.mode === 'new' ? 'New record' : 'Edit record'))
const description = computed(() =>
  props.mode === 'new'
    ? 'Create a new record in this collection.'
    : 'Update the selected record.'
)

function fileLabel(value: unknown): string {
  if (Array.isArray(value)) return `${value.length} file(s) attached`
  if (value && typeof value === 'object' && 'name' in value) {
    return String((value as { name?: string }).name || 'file')
  }
  return 'No file uploaded'
}

function selectedFileLabel(fieldName: string): string {
  const files = fileSelections.value[fieldName] || []
  if (files.length === 0) return ''
  if (files.length === 1) return `${files[0].name} (${formatByteSize(files[0].size)})`
  return `${files.length} files selected`
}

function onFileChange(fieldName: string, event: Event) {
  const input = event.target as HTMLInputElement
  fileSelections.value[fieldName] = input.files ? Array.from(input.files) : []
}

function counterFor(field: FieldDefinition) {
  const value = field.type === 'JSON' ? form.value.jsonText[field.name] : form.value.values[field.name]
  return fieldCounter(field, value)
}

/**
 * Re-checks a field that is already marked. Nothing turns red while it is being typed for the
 * first time - but once a message is showing, it should disappear the moment the value is fixed
 * rather than at the next save.
 */
function onFieldInput(field: FieldDefinition) {
  if (!errors.value[field.name]) return
  try {
    const values = serializeRecordForm(props.fields, form.value, props.mode)
    const message = validateFieldValue(field, values[field.name])
    if (message) {
      errors.value[field.name] = message
    } else {
      delete errors.value[field.name]
    }
  } catch {
    // A field that cannot even be serialized yet (half-typed JSON) keeps its message.
  }
}

async function focusFirstError() {
  await nextTick()
  const invalid = body.value?.querySelector<HTMLElement>('[aria-invalid="true"]')
  invalid?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  invalid?.focus({ preventScroll: true })
}

function submit() {
  try {
    if (view.value === 'json') {
      const payload = JSON.parse(jsonState.value) as Record<string, unknown>
      const issues = validateRecordValues(props.fields, payload)
      if (issues.length > 0) {
        // The JSON view has no field to mark, so the message carries the field name itself.
        emit('validation-error', `${issues[0].field}: ${issues[0].message}`)
        return
      }
      emit('save', { values: payload, files: {} })
      return
    }

    // Only schema fields are serialized, so id/createdAt/updatedAt of the edited record cannot
    // travel back into the request - the API rejects them as read-only.
    const values = serializeRecordForm(props.fields, form.value, props.mode)

    const issues = validateRecordValues(props.fields, values)
    errors.value = Object.fromEntries(issues.map((issue) => [issue.field, issue.message]))
    if (issues.length > 0) {
      // The messages are at the fields; the toast only says how many there are and that nothing
      // was saved.
      emit(
        'validation-error',
        issues.length === 1 ? '1 field needs attention' : `${issues.length} fields need attention`
      )
      void focusFirstError()
      return
    }

    emit('save', { values, files: { ...fileSelections.value } })
  } catch (error) {
    emit('validation-error', error instanceof Error ? error.message : 'Invalid record data')
  }
}
</script>

<template>
  <USlideover
    :open="open"
    :title="title"
    :description="description"
    :ui="slideoverUi"
    @update:open="emit('update:open', $event)"
  >
    <template #body>
      <div ref="body" class="w-full space-y-4">
        <PField v-if="mode === 'edit'" label="ID" icon="i-lucide-fingerprint">
          <UInput :model-value="String(record.id || '')" readonly class="font-mono">
            <template #trailing>
              <CopyButton size="xs" :value="String(record.id || '')" label="Copy record id" />
            </template>
          </UInput>
        </PField>

        <SegmentedControl v-model="view" :items="viewItems" aria-label="Editor view" />

        <div v-if="view === 'form'" class="space-y-4">
          <PField
            v-for="field in fields"
            :key="field.name"
            :label="fieldDisplayName(field.name)"
            :icon="fieldTypeIcon(field.type)"
            :optional="!field.required"
            :error="errors[field.name]"
            :hint="fieldConstraintHint(field)"
            :counter="counterFor(field)?.text"
            :counter-exceeded="counterFor(field)?.exceeded"
          >
            <template v-if="field.type === 'FILE'">
              <p class="mb-2 text-sm text-muted">
                {{ selectedFileLabel(field.name) || fileLabel(form.values[field.name]) }}
              </p>
              <input
                type="file"
                class="block w-full text-sm file:me-3 file:rounded-md file:border-0 file:bg-elevated file:px-3 file:py-1.5 file:text-sm file:font-medium file:text-default hover:file:bg-accented"
                :multiple="(field.options?.maxSelect || 1) > 1"
                :accept="(field.options?.mimeTypes || []).join(',') || undefined"
                @change="onFileChange(field.name, $event)"
              />
            </template>
            <FieldValueInput
              v-else-if="field.type === 'JSON'"
              v-model="form.jsonText[field.name]"
              :field="field"
              @update:model-value="onFieldInput(field)"
            />
            <FieldValueInput
              v-else
              v-model="form.values[field.name]"
              :field="field"
              @update:model-value="onFieldInput(field)"
            />
          </PField>
        </div>

        <PField v-else label="Document" icon="i-lucide-braces" help="The record as it is sent to the API.">
          <UTextarea v-model="jsonState" :rows="16" class="w-full font-mono" />
        </PField>
      </div>
    </template>

    <template #footer>
      <div class="flex w-full flex-col gap-2">
        <div class="flex gap-2">
          <UButton class="flex-1" :loading="saving" icon="i-lucide-save" @click="submit">
            {{ mode === 'new' ? 'Create record' : 'Save record' }}
          </UButton>
          <UButton variant="ghost" color="neutral" @click="emit('update:open', false)">
            Cancel
          </UButton>
        </div>
        <UButton
          v-if="mode === 'edit'"
          block
          color="error"
          variant="soft"
          icon="i-lucide-trash-2"
          :loading="saving"
          @click="emit('delete')"
        >
          Delete record
        </UButton>
      </div>
    </template>
  </USlideover>
</template>
