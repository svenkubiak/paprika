<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import OverlayDrawer from '@/components/OverlayDrawer.vue'
import FieldLabelHelp from '@/components/FieldLabelHelp.vue'
import { selectContentProps, selectMenuUi } from '@/lib/overlay-ui'
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
  delete: []
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

const title = computed(() =>
  props.mode === 'add' ? 'Add index' : `Edit index${props.index?.name ? `: ${props.index.name}` : ''}`
)

const description = computed(() =>
  props.mode === 'add'
    ? 'Define a new index for this collection.'
    : 'Update this index. Renaming it drops the index and builds it again.'
)

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
  <OverlayDrawer
    :open="open"
    side="right"
    width-class="w-full max-w-xl"
    labelled-by="index-editor-title"
    @update:open="emit('update:open', $event)"
  >
    <template #default="{ close }">
      <div class="flex items-start justify-between gap-3 border-b border-default p-4 sm:px-6">
        <div class="min-w-0">
          <h2 id="index-editor-title" class="font-semibold">{{ title }}</h2>
          <p class="mt-1 text-sm text-muted">{{ description }}</p>
        </div>
        <UButton
          icon="i-lucide-x"
          variant="ghost"
          color="neutral"
          aria-label="Close index editor"
          @click="close"
        />
      </div>

      <div class="flex-1 overflow-y-auto p-4 sm:p-6">
        <UAlert
          v-if="error"
          color="error"
          variant="soft"
          icon="i-lucide-circle-x"
          :title="error"
          class="mb-4"
        />

        <UCard variant="subtle" :ui="{ body: 'space-y-4 p-4 sm:p-4' }">
          <UFormField required class="w-full">
            <template #label>
              <FieldLabelHelp
                label="Name"
                hint="Identifies the index on the server. Renaming one drops it and builds it again."
              />
            </template>
            <UInput v-model="name" class="w-full font-mono" autofocus />
          </UFormField>

          <div class="flex items-center justify-between gap-3">
            <FieldLabelHelp
              label="Unique"
              hint="Rejects records whose indexed values, taken together, already exist. Cannot be enabled while the collection still holds duplicates."
            />
            <USwitch v-model="unique" />
          </div>

          <div class="space-y-2 border-t border-default pt-4">
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
                :content="selectContentProps"
                :ui="selectMenuUi"
                class="min-w-0 flex-1 font-mono"
                @update:model-value="setField(position, String($event))"
              />
              <USelect
                :model-value="entry.direction"
                :items="DIRECTIONS"
                :content="selectContentProps"
                :ui="selectMenuUi"
                class="w-36"
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
        </UCard>
      </div>

      <div class="flex flex-col gap-2 border-t border-default p-4 sm:flex-row sm:px-6">
        <UButton class="flex-1" :loading="saving" icon="i-lucide-save" @click="submit">
          {{ mode === 'add' ? 'Create index' : 'Save index' }}
        </UButton>
        <UButton
          v-if="mode === 'edit'"
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
