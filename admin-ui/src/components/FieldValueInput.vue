<script setup lang="ts">
/**
 * The input widget for one schema field, shared by the record editor and the user editor.
 *
 * The model value is the *form* representation of the field as produced by `buildRecordFormState`
 * (text for most types, the select sentinels for BOOLEAN/SELECT, the picker format for dates, and
 * the raw text for JSON) - not the value that is stored. Converting back is the job of
 * `serializeRecordForm`, so both editors send the same thing for the same input.
 *
 * Everything around the control - label, constraint line, error, counter - belongs to `PField`.
 * What lives here is only what depends on the field *type*.
 *
 * FILE fields are not handled here: their value is a file the parent has to collect and upload.
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

/**
 * "No value" reads as the exception, so it goes last - the two answers to the question come
 * first. A required boolean has no third option and is a plain true/false pair.
 */
const booleanItems = computed(() => {
  const items = booleanFieldSelectItems(props.field)
  const unset = items.filter((item) => item.value === BOOLEAN_UNSET)
  return [...items.filter((item) => item.value !== BOOLEAN_UNSET), ...unset]
})

/**
 * Whether the text in a JSON field parses, checked while it is typed. This is a `JSON.parse` in
 * the browser - the editor used to accept anything and let the save fail, which meant retyping a
 * missing brace after a round trip to the server.
 */
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
    // Unparseable text cannot be formatted - the status line beside the button already says so.
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
        :class="jsonStatus.ok ? 'text-success' : 'text-error'"
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

  <!--
    Three states, all of them visible: a select would hide true and false behind a menu, and the
    "No value" entry in it looked like one more option rather than the absence of one.
  -->
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
    The clear button sits beside the picker, not in its trailing slot: a date, time or datetime
    input paints the browser's own calendar/clock indicator at its right edge, and an overlay there
    covers exactly the control the user reaches for. It keeps its place when there is nothing to
    clear, so setting a value does not resize the input next to it.
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
