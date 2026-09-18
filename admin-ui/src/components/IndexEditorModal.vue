<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import FieldLabelHelp from '@/components/FieldLabelHelp.vue'
import { modalUi } from '@/lib/overlay-ui'
import type { IndexDefinition, IndexDirection, IndexField } from '@/types'

const props = defineProps<{
  open: boolean
  mode: 'add' | 'edit'
  index: IndexDefinition | null
  /** Schema fields plus the system fields an index may be built on. */
  availableFields: string[]
  /** Names of all other indexes, so a collision is caught before the request. */
  takenNames: string[]
  saving: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  save: [index: IndexDefinition]
}>()

const DIRECTIONS = [
  { label: 'Ascending', value: 'ASC' },
  { label: 'Descending', value: 'DESC' }
]

const name = ref('')
const unique = ref(false)
const fields = ref<IndexField[]>([])
const error = ref('')

const fieldItems = computed(() => props.availableFields.map((field) => ({ label: field, value: field })))

// A compound index is ordered: MongoDB can only serve a query from a prefix of it, so which field
// comes first is part of what the index is, not a detail of how it is rendered.
const canMoveUp = (position: number) => position > 0
const canMoveDown = (position: number) => position < fields.value.length - 1

watch(
  () => props.open,
  (isOpen) => {
    if (!isOpen) {
      return
    }

    error.value = ''
    name.value = props.index?.name ?? ''
    unique.value = props.index?.unique ?? false
    fields.value = props.index
      ? props.index.fields.map((field) => ({ ...field }))
      : [{ field: props.availableFields[0] ?? '', direction: 'ASC' }]
  },
  { immediate: true }
)

function addField() {
  const used = new Set(fields.value.map((field) => field.field))
  const next = props.availableFields.find((field) => !used.has(field))
  fields.value = [...fields.value, { field: next ?? '', direction: 'ASC' }]
}

function removeField(position: number) {
  fields.value = fields.value.filter((_, current) => current !== position)
}

function move(position: number, offset: number) {
  const next = [...fields.value]
  const [entry] = next.splice(position, 1)
  next.splice(position + offset, 0, entry)
  fields.value = next
}

function setField(position: number, value: string) {
  fields.value = fields.value.map((entry, current) =>
    current === position ? { ...entry, field: value } : entry
  )
}

function setDirection(position: number, value: IndexDirection) {
  fields.value = fields.value.map((entry, current) =>
    current === position ? { ...entry, direction: value } : entry
  )
}

function submit() {
  const trimmed = name.value.trim()
  error.value = ''

  if (!trimmed) {
    error.value = 'Index name is required'
    return
  }
  if (props.takenNames.includes(trimmed)) {
    error.value = `An index named "${trimmed}" already exists`
    return
  }
  if (fields.value.length === 0) {
    error.value = 'An index needs at least one field'
    return
  }
  if (fields.value.some((entry) => !entry.field)) {
    error.value = 'Every index entry needs a field'
    return
  }

  const used = new Set<string>()
  for (const entry of fields.value) {
    if (used.has(entry.field)) {
      error.value = `Field "${entry.field}" is used twice in this index`
      return
    }
    used.add(entry.field)
  }

  emit('save', {
    name: trimmed,
    unique: unique.value,
    fields: fields.value.map((entry) => ({ ...entry }))
  })
}
</script>

<template>
  <UModal
    :open="open"
    portal="body"
    :ui="modalUi"
    @update:open="emit('update:open', $event)"
  >
    <template #content>
      <UCard>
        <template #header>
          <div class="flex items-center gap-2">
            <UIcon name="i-lucide-list-ordered" class="size-5 text-primary" />
            <h3 class="font-semibold">{{ mode === 'add' ? 'New index' : 'Edit index' }}</h3>
          </div>
        </template>

        <UAlert
          v-if="error"
          color="error"
          variant="soft"
          icon="i-lucide-circle-x"
          :title="error"
          class="mb-4"
        />

        <form class="w-full space-y-4" @submit.prevent="submit">
          <UFormField required class="w-full">
            <template #label>
              <FieldLabelHelp
                label="Name"
                hint="Identifies the index on the server. Renaming one drops it and builds it again."
              />
            </template>
            <UInput v-model="name" class="w-full font-mono" autofocus />
          </UFormField>

          <UFormField class="w-full">
            <template #label>
              <FieldLabelHelp
                label="Unique"
                hint="Rejects records whose indexed values, taken together, already exist. Cannot be enabled while the collection still holds duplicates."
              />
            </template>
            <USwitch v-model="unique" />
          </UFormField>

          <div class="space-y-2">
            <FieldLabelHelp
              label="Fields"
              hint="Order matters: a compound index only serves queries that start with its leading fields."
            />

            <div
              v-for="(entry, position) in fields"
              :key="position"
              class="flex items-center gap-2"
            >
              <USelect
                :model-value="entry.field"
                :items="fieldItems"
                class="min-w-0 flex-1"
                @update:model-value="setField(position, String($event))"
              />
              <USelect
                :model-value="entry.direction"
                :items="DIRECTIONS"
                class="w-40"
                @update:model-value="setDirection(position, $event as IndexDirection)"
              />
              <UButton
                size="sm"
                color="neutral"
                variant="ghost"
                icon="i-lucide-chevron-up"
                :disabled="!canMoveUp(position)"
                aria-label="Move field up"
                @click="move(position, -1)"
              />
              <UButton
                size="sm"
                color="neutral"
                variant="ghost"
                icon="i-lucide-chevron-down"
                :disabled="!canMoveDown(position)"
                aria-label="Move field down"
                @click="move(position, 1)"
              />
              <UButton
                size="sm"
                color="error"
                variant="ghost"
                icon="i-lucide-trash-2"
                :disabled="fields.length < 2"
                aria-label="Remove field"
                @click="removeField(position)"
              />
            </div>

            <UButton
              size="sm"
              variant="soft"
              icon="i-lucide-plus"
              :disabled="fields.length >= availableFields.length"
              @click="addField"
            >
              Add field
            </UButton>
          </div>

          <div class="flex justify-end gap-2">
            <UButton variant="ghost" color="neutral" @click="emit('update:open', false)">
              Cancel
            </UButton>
            <UButton type="submit" :loading="saving" icon="i-lucide-save">
              {{ mode === 'add' ? 'Create index' : 'Save index' }}
            </UButton>
          </div>
        </form>
      </UCard>
    </template>
  </UModal>
</template>
