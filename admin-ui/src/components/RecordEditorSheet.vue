<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { FieldDefinition } from '@/types'
import { selectContentProps, selectMenuUi, slideoverUi } from '@/lib/overlay-ui'
import { fieldDisplayName } from '@/lib/utils'
import {
  booleanFieldSelectItems,
  booleanToFormValue,
  serializeBooleanFieldValue,
  validateRecordValues
} from '@/lib/field-validation'

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
// Dynamic field widgets narrow values at runtime based on the collection schema.
const formState = ref<Record<string, any>>({})
const jsonState = ref('')
const jsonFieldState = ref<Record<string, string>>({})
const fileSelections = ref<Record<string, File[]>>({})

function serializeJsonField(value: unknown): string {
  if (value === null || value === undefined) return ''
  return JSON.stringify(value, null, 2)
}

watch(
  () => [props.record, props.fields] as const,
  ([record, fields]) => {
    formState.value = { ...record }
    fileSelections.value = {}
    jsonFieldState.value = {}
    for (const field of fields) {
      if (field.type === 'JSON') {
        jsonFieldState.value[field.name] = serializeJsonField(record[field.name])
      }
      if (field.type === 'RELATION' && (field.options?.maxSelect || 1) > 1 && Array.isArray(record[field.name])) {
        formState.value[field.name] = (record[field.name] as string[]).join(', ')
      }
      if (field.type === 'BOOLEAN') {
        formState.value[field.name] = booleanToFormValue(record[field.name])
      }
    }
    const payload = { ...record }
    delete payload.id
    delete payload.createdAt
    delete payload.updatedAt
    delete payload.created
    delete payload.updated
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

function selectItems(field: FieldDefinition) {
  return (field.options?.values || []).map((value) => ({
    label: value,
    value
  }))
}

function isMultiSelect(field: FieldDefinition) {
  return (field.options?.maxSelect || 1) > 1
}

function isMultiRelation(field: FieldDefinition) {
  return field.type === 'RELATION' && isMultiSelect(field)
}

function relationPlaceholder(field: FieldDefinition) {
  return isMultiRelation(field) ? 'id-1, id-2' : 'Related record id'
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

    const values = { ...formState.value }
    for (const field of props.fields) {
      if (field.type === 'FILE') {
        delete values[field.name]
      }
      if (field.type === 'NUMBER') {
        const raw = values[field.name]
        if (raw === '' || raw === null || raw === undefined) {
          values[field.name] = undefined
        } else {
          const parsed = Number(raw)
          if (Number.isNaN(parsed)) {
            throw new Error(`${field.name} must be a number`)
          }
          values[field.name] = parsed
        }
      }
      if (field.type === 'RELATION' && isMultiRelation(field)) {
        const raw = values[field.name]
        if (raw === '' || raw === null || raw === undefined) {
          values[field.name] = undefined
        } else if (typeof raw === 'string') {
          values[field.name] = raw
            .split(',')
            .map((item) => item.trim())
            .filter(Boolean)
        }
      }
      if (field.type === 'BOOLEAN') {
        const serialized = serializeBooleanFieldValue(values[field.name], field, props.mode)
        if (serialized === undefined) {
          delete values[field.name]
        } else {
          values[field.name] = serialized
        }
      }
      if (field.type === 'JSON') {
        delete values[field.name]
        const raw = jsonFieldState.value[field.name]?.trim()
        if (!raw) {
          if (field.required) {
            throw new Error(`JSON field "${field.name}" is required`)
          }
          continue
        }
        values[field.name] = JSON.parse(raw)
      }
    }

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
              <p class="mb-2 text-sm text-muted">{{ fileLabel(formState[field.name]) }}</p>
              <input
                type="file"
                class="block w-full text-sm"
                :multiple="(field.options?.maxSelect || 1) > 1"
                @change="onFileChange(field.name, $event)"
              />
            </template>
            <UTextarea
              v-else-if="field.type === 'JSON'"
              v-model="jsonFieldState[field.name]"
              :rows="8"
              class="w-full font-mono"
              placeholder="{&#10;  &quot;key&quot;: &quot;value&quot;&#10;}"
            />
            <USelect
              v-else-if="field.type === 'SELECT'"
              v-model="formState[field.name]"
              :items="selectItems(field)"
              :multiple="isMultiSelect(field)"
              placeholder="Select value"
              :content="selectContentProps"
              :ui="selectMenuUi"
              class="w-full"
            />
            <UInput
              v-else-if="field.type === 'RELATION'"
              v-model="formState[field.name]"
              class="w-full font-mono"
              :placeholder="relationPlaceholder(field)"
            />
            <USelect
              v-else-if="field.type === 'BOOLEAN'"
              v-model="formState[field.name]"
              :items="booleanFieldSelectItems(field)"
              :content="selectContentProps"
              :ui="selectMenuUi"
              class="w-full"
            />
            <UInput
              v-else
              v-model="formState[field.name]"
              class="w-full"
              :type="field.type === 'NUMBER' ? 'number' : 'text'"
              :step="field.type === 'NUMBER' ? 'any' : undefined"
            />
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
