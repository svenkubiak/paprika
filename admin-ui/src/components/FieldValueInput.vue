<script setup lang="ts">
/**
 * The model value is the form representation from `buildRecordFormState`, not the stored value;
 * `serializeRecordForm` converts back. FILE fields are uploaded by the parent, not handled here.
 */
import { computed } from 'vue'
import type { FieldDefinition } from '@/types'
import SegmentedControl from '@/components/SegmentedControl.vue'
import { selectContentProps, selectMenuUi } from '@/lib/overlay-ui'
import {
  BOOLEAN_UNSET,
  booleanFieldSelectItems,
  selectFieldSelectItems
} from '@/lib/field-validation'

const props = defineProps<{
  field: FieldDefinition
  modelValue: unknown
}>()

const emit = defineEmits<{
  'update:modelValue': [value: unknown]
}>()

function isMultiSelect(field: FieldDefinition) {
  return (field.options?.maxSelect || 1) > 1
}

function dateInputType(field: FieldDefinition): 'date' | 'time' | 'datetime-local' {
  if (field.type === 'DATE') return 'date'
  if (field.type === 'TIME') return 'time'
  return 'datetime-local'
}

function relationPlaceholder(field: FieldDefinition) {
  return isMultiSelect(field) ? 'id-1, id-2' : 'Related record id'
}

function isDateLike(field: FieldDefinition) {
  return field.type === 'DATE' || field.type === 'TIME' || field.type === 'DATETIME'
}

function hasValue(): boolean {
  const value = props.modelValue
  return !(value === null || value === undefined || (typeof value === 'string' && !value.trim()))
}

function update(value: unknown) {
  emit('update:modelValue', value)
}

const booleanItems = computed(() => {
  const items = booleanFieldSelectItems(props.field)
  const unset = items.filter((item) => item.value === BOOLEAN_UNSET)
  return [...items.filter((item) => item.value !== BOOLEAN_UNSET), ...unset]
})

const jsonStatus = computed(() => {
  if (props.field.type !== 'JSON') return undefined
  const text = typeof props.modelValue === 'string' ? props.modelValue.trim() : ''
  if (!text) return undefined
  try {
    JSON.parse(text)
    return { ok: true, message: 'Valid JSON' }
  } catch (error) {
    return { ok: false, message: error instanceof Error ? error.message : 'Invalid JSON' }
  }
})

function formatJson() {
  const text = typeof props.modelValue === 'string' ? props.modelValue : ''
  try {
    update(JSON.stringify(JSON.parse(text), null, 2))
  } catch {
    // Unparseable text cannot be formatted; the status line beside the button already says so.
  }
}
</script>

<template>
  <div v-if="field.type === 'JSON'" class="w-full space-y-1.5">
    <UTextarea
      :model-value="(modelValue as string) ?? ''"
      :rows="8"
      class="w-full font-mono"
      placeholder="{&#10;  &quot;key&quot;: &quot;value&quot;&#10;}"
      @update:model-value="update($event)"
    />
    <div v-if="jsonStatus" class="flex items-center gap-2 text-xs">
      <span
        class="inline-flex items-center gap-1.5 font-medium"
        :class="jsonStatus.ok ? 'text-readable-success' : 'text-readable-error'"
      >
        <UIcon
          :name="jsonStatus.ok ? 'i-lucide-circle-check' : 'i-lucide-circle-x'"
          class="size-3.5 shrink-0"
        />
        {{ jsonStatus.message }}
      </span>
      <UButton
        v-if="jsonStatus.ok"
        class="ms-auto"
        size="xs"
        variant="ghost"
        color="neutral"
        icon="i-lucide-align-left"
        @click="formatJson"
      >
        Format
      </UButton>
    </div>
  </div>

  <UTextarea
    v-else-if="field.type === 'STRING' && field.options?.multiline === true"
    :model-value="(modelValue as string) ?? ''"
    :rows="8"
    class="w-full"
    @update:model-value="update($event)"
  />

  <USelect
    v-else-if="field.type === 'SELECT'"
    :model-value="(modelValue as string | string[] | undefined)"
    :items="selectFieldSelectItems(field)"
    :multiple="isMultiSelect(field)"
    placeholder="Select value"
    :content="selectContentProps"
    :ui="selectMenuUi"
    class="w-full"
    @update:model-value="update($event)"
  />

  <SegmentedControl
    v-else-if="field.type === 'BOOLEAN'"
    :model-value="(modelValue as string) ?? BOOLEAN_UNSET"
    :items="booleanItems"
    :muted-value="BOOLEAN_UNSET"
    :aria-label="field.name"
    @update:model-value="update($event)"
  />

  <UInput
    v-else-if="field.type === 'RELATION'"
    :model-value="(modelValue as string) ?? ''"
    class="w-full font-mono"
    :placeholder="relationPlaceholder(field)"
    @update:model-value="update($event)"
  />

  <!--
    The clear button sits beside the picker: the browser paints its calendar/clock indicator in the
    trailing slot. It keeps its place when empty so the input does not resize.
  -->
  <div v-else-if="isDateLike(field)" class="flex w-full items-center gap-2">
    <UInput
      :model-value="(modelValue as string) ?? ''"
      :type="dateInputType(field)"
      :step="field.type === 'DATE' ? undefined : '1'"
      class="min-w-0 flex-1"
      @update:model-value="update($event)"
    />
    <UButton
      color="neutral"
      variant="ghost"
      size="sm"
      icon="i-lucide-x"
      :class="{ invisible: !hasValue() }"
      :disabled="!hasValue()"
      aria-label="Clear value"
      title="Clear value"
      @click="update('')"
    />
  </div>

  <UInput
    v-else
    :model-value="modelValue === null || modelValue === undefined ? '' : String(modelValue)"
    class="w-full"
    :type="field.type === 'NUMBER' ? 'number' : 'text'"
    :step="field.type === 'NUMBER' ? 'any' : undefined"
    @update:model-value="update($event)"
  />
</template>
