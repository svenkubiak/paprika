<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { FieldDefinition } from '@/types'
import FieldValueInput from '@/components/FieldValueInput.vue'
import { slideoverUi } from '@/lib/overlay-ui'
import { fieldDisplayName } from '@/lib/utils'
import { validateRecordValues } from '@/lib/field-validation'
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

watch(
  () => [props.record, props.fields] as const,
  ([record, fields]) => {
    form.value = buildRecordFormState(record, fields, props.mode)
    fileSelections.value = {}

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

function onFileChange(fieldName: string, event: Event) {
  const input = event.target as HTMLInputElement
  fileSelections.value[fieldName] = input.files ? Array.from(input.files) : []
}

function submit() {
  try {
    if (view.value === 'json') {
      const payload = JSON.parse(jsonState.value) as Record<string, unknown>
      const issues = validateRecordValues(props.fields, payload)
      if (issues.length > 0) {
        emit('validation-error', issues[0].message)
        return
      }
      emit('save', { values: payload, files: {} })
      return
    }

    // Only schema fields are serialized, so id/createdAt/updatedAt of the edited record cannot
    // travel back into the request - the API rejects them as read-only.
    const values = serializeRecordForm(props.fields, form.value, props.mode)

    const issues = validateRecordValues(props.fields, values)
    if (issues.length > 0) {
      emit('validation-error', issues[0].message)
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
      <div class="w-full space-y-4">
        <UFormField v-if="mode === 'edit'" label="ID" class="w-full">
          <UInput :model-value="String(record.id || '')" readonly class="w-full font-mono" />
        </UFormField>

        <div class="inline-flex rounded-lg border border-default p-1">
          <UButton
            size="sm"
            :variant="view === 'form' ? 'soft' : 'ghost'"
            @click="view = 'form'"
          >
            Form
          </UButton>
          <UButton
            size="sm"
            :variant="view === 'json' ? 'soft' : 'ghost'"
            @click="view = 'json'"
          >
            JSON
          </UButton>
        </div>

        <div v-if="view === 'form'" class="space-y-4">
          <UFormField
            v-for="field in fields"
            :key="field.name"
            :label="fieldDisplayName(field.name)"
            :required="field.required"
            class="w-full"
          >
            <template v-if="field.type === 'FILE'">
              <p class="mb-2 text-sm text-muted">{{ fileLabel(form.values[field.name]) }}</p>
              <input
                type="file"
                class="block w-full text-sm"
                :multiple="(field.options?.maxSelect || 1) > 1"
                @change="onFileChange(field.name, $event)"
              />
            </template>
            <FieldValueInput
              v-else-if="field.type === 'JSON'"
              :field="field"
              v-model="form.jsonText[field.name]"
            />
            <FieldValueInput v-else :field="field" v-model="form.values[field.name]" />
          </UFormField>
        </div>

        <UFormField v-else label="Document" class="w-full">
          <UTextarea v-model="jsonState" :rows="16" class="w-full font-mono" />
        </UFormField>
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
