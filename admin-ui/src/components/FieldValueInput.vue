<script setup lang="ts">
/**
 * The input widget for one schema field, shared by the record editor and the user editor.
 *
 * The model value is the *form* representation of the field as produced by `buildRecordFormState`
 * (text for most types, the select sentinels for BOOLEAN/SELECT, the picker format for dates, and
 * the raw text for JSON) - not the value that is stored. Converting back is the job of
 * `serializeRecordForm`, so both editors send the same thing for the same input.
 *
 * FILE fields are not handled here: their value is a file the parent has to collect and upload.
 */
import type { FieldDefinition } from '@/types'
import { selectContentProps, selectMenuUi } from '@/lib/overlay-ui'
import {
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
</script>

<template>
  <UTextarea
    v-if="field.type === 'JSON'"
    :model-value="(modelValue as string) ?? ''"
    :rows="8"
    class="w-full font-mono"
    placeholder="{&#10;  &quot;key&quot;: &quot;value&quot;&#10;}"
    @update:model-value="update($event)"
  />

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

  <USelect
    v-else-if="field.type === 'BOOLEAN'"
    :model-value="(modelValue as string | undefined)"
    :items="booleanFieldSelectItems(field)"
    :content="selectContentProps"
    :ui="selectMenuUi"
    class="w-full"
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
    The clear button sits beside the picker, not in its trailing slot: a date, time or datetime
    input paints the browser's own calendar/clock indicator at its right edge, and an overlay there
    covers exactly the control the user reaches for.
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
      v-if="hasValue()"
      color="neutral"
      variant="ghost"
      size="sm"
      icon="i-lucide-x"
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
