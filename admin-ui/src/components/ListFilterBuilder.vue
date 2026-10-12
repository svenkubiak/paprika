<script setup lang="ts">
/**
 * The filter rows of the data view. Every row is one condition of the list API; the page decides
 * when to apply them. The rules for operators and values live in `@/lib/list-filters`.
 */
import { computed } from 'vue'
import { selectContentProps, selectMenuUi } from '@/lib/overlay-ui'
import {
  changeField,
  conditionIssue,
  OPERATOR_LABELS,
  operatorsFor,
  takesList,
  takesNoValue,
  type FilterCondition,
  type FilterField,
  type FilterOperator
} from '@/lib/list-filters'

const props = defineProps<{
  fields: FilterField[]
  modelValue: FilterCondition[]
  /** The server's answer to the last applied filters, shown instead of a toast. */
  error?: string | null
  /** The list request the current filters and sort produce. */
  requestPath: string
  readableRequestPath: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: FilterCondition[]]
  clear: []
}>()

const fieldItems = computed(() => props.fields.map((field) => ({ label: field.name, value: field.name })))

function fieldOf(condition: FilterCondition): FilterField | undefined {
  return props.fields.find((field) => field.name === condition.field)
}

function operatorItems(condition: FilterCondition) {
  const field = fieldOf(condition)
  return (field ? operatorsFor(field.type) : []).map((operator) => ({
    label: OPERATOR_LABELS[operator],
    value: operator
  }))
}

function inputType(field: FilterField | undefined): string {
  switch (field?.type) {
    case 'NUMBER':
      return 'number'
    case 'DATE':
      return 'date'
    case 'TIME':
      return 'time'
    case 'DATETIME':
      return 'datetime-local'
    default:
      return 'text'
  }
}

// Seconds for time and date & time, so a value like 10:00:30 can be entered
function inputStep(field: FilterField | undefined): string | undefined {
  if (field?.type === 'NUMBER') return 'any'
  if (field?.type === 'TIME' || field?.type === 'DATETIME') return '1'
  return undefined
}

function usesOptions(condition: FilterCondition): boolean {
  return fieldOf(condition)?.type === 'SELECT' && condition.operator !== 'contains'
}

function optionItems(condition: FilterCondition) {
  return (fieldOf(condition)?.options ?? []).map((value) => ({ label: value, value }))
}

const booleanItems = [
  { label: 'true', value: 'true' },
  { label: 'false', value: 'false' }
]

function replace(index: number, next: FilterCondition) {
  const conditions = [...props.modelValue]
  conditions[index] = next
  emit('update:modelValue', conditions)
}

function setField(index: number, name: string) {
  const field = props.fields.find((candidate) => candidate.name === name)
  if (field) replace(index, changeField(props.modelValue[index], field))
}

function setOperator(index: number, operator: FilterOperator) {
  const current = props.modelValue[index]
  // A list and a single value do not convert into each other
  const keepsValue = takesList(current.operator) === takesList(operator)
  replace(index, {
    ...current,
    operator,
    value: keepsValue ? current.value : '',
    values: keepsValue ? current.values : []
  })
}

function setValue(index: number, value: unknown) {
  replace(index, { ...props.modelValue[index], value: value === null || value === undefined ? '' : String(value) })
}

function setValues(index: number, values: unknown) {
  replace(index, { ...props.modelValue[index], values: Array.isArray(values) ? values.map(String) : [] })
}

function remove(index: number) {
  emit('update:modelValue', props.modelValue.filter((_, position) => position !== index))
}
</script>

<template>
  <div class="space-y-3 rounded-md border border-default p-3">
    <div
      v-for="(condition, index) in modelValue"
      :key="condition.id"
      class="space-y-1"
    >
      <div class="flex flex-wrap items-center gap-2">
        <USelect
          :model-value="condition.field"
          :items="fieldItems"
          :content="selectContentProps"
          :ui="selectMenuUi"
          aria-label="Filter field"
          class="w-full min-w-0 sm:w-44"
          @update:model-value="setField(index, String($event))"
        />
        <USelect
          :model-value="condition.operator"
          :items="operatorItems(condition)"
          :content="selectContentProps"
          :ui="selectMenuUi"
          aria-label="Filter operator"
          class="w-full min-w-0 sm:w-36"
          @update:model-value="setOperator(index, $event as FilterOperator)"
        />

        <template v-if="!takesNoValue(condition.operator)">
          <template v-if="takesList(condition.operator)">
            <USelect
              v-if="usesOptions(condition)"
              :model-value="condition.values"
              :items="optionItems(condition)"
              multiple
              placeholder="Select values"
              :content="selectContentProps"
              :ui="selectMenuUi"
              aria-label="Filter values"
              class="w-full min-w-0 sm:flex-1"
              @update:model-value="setValues(index, $event)"
            />
            <UInputTags
              v-else
              :model-value="condition.values"
              placeholder="Type a value, then Enter"
              aria-label="Filter values"
              class="w-full min-w-0 sm:flex-1"
              @update:model-value="setValues(index, $event)"
            />
          </template>
          <USelect
            v-else-if="usesOptions(condition)"
            :model-value="condition.value || undefined"
            :items="optionItems(condition)"
            placeholder="Select value"
            :content="selectContentProps"
            :ui="selectMenuUi"
            aria-label="Filter value"
            class="w-full min-w-0 sm:flex-1"
            @update:model-value="setValue(index, $event)"
          />
          <USelect
            v-else-if="fieldOf(condition)?.type === 'BOOLEAN'"
            :model-value="condition.value || undefined"
            :items="booleanItems"
            placeholder="Select value"
            :content="selectContentProps"
            :ui="selectMenuUi"
            aria-label="Filter value"
            class="w-full min-w-0 sm:flex-1"
            @update:model-value="setValue(index, $event)"
          />
          <UInput
            v-else
            :model-value="condition.value"
            :type="inputType(fieldOf(condition))"
            :step="inputStep(fieldOf(condition))"
            placeholder="Value"
            aria-label="Filter value"
            class="w-full min-w-0 sm:flex-1"
            @update:model-value="setValue(index, $event)"
          />
        </template>
        <div v-else class="hidden sm:block sm:flex-1" />

        <UButton
          color="neutral"
          variant="ghost"
          icon="i-lucide-x"
          aria-label="Remove filter"
          title="Remove filter"
          @click="remove(index)"
        />
      </div>
      <p
        v-if="conditionIssue(condition, modelValue, fields)"
        class="text-xs font-medium text-readable-error"
      >
        {{ conditionIssue(condition, modelValue, fields) }}
      </p>
    </div>

    <p v-if="error" class="flex items-start gap-1.5 text-xs font-medium text-readable-error">
      <UIcon name="i-lucide-circle-x" class="mt-0.5 size-3.5 shrink-0" />
      <span>{{ error }}</span>
    </p>

    <div class="flex flex-wrap items-center gap-2 border-t border-default pt-3">
      <div class="flex min-w-0 flex-1 items-center gap-1">
        <code class="min-w-0 truncate text-xs text-muted" :title="readableRequestPath">
          GET {{ readableRequestPath }}
        </code>
        <CopyButton :value="requestPath" label="Copy request URL" size="xs" />
      </div>
      <UButton size="sm" variant="ghost" color="neutral" icon="i-lucide-filter-x" @click="emit('clear')">
        Clear filters
      </UButton>
    </div>
  </div>
</template>
