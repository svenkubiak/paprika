<script setup lang="ts">
import { computed } from 'vue'
import OverlayDrawer from '@/components/OverlayDrawer.vue'
import FieldValueInput from '@/components/FieldValueInput.vue'
import PField from '@/components/PField.vue'
import { fieldConstraintHint, fieldCounter } from '@/lib/field-constraints'
import { fieldDisplayName, fieldTypeIcon } from '@/lib/utils'
import type { FieldDefinition, TenantUser } from '@/types'
import type { RecordFormState } from '@/lib/record-form'

export type UserEditorMode = 'add' | 'edit'

export interface UserEditorForm {
  username: string
  password: string
  email: string
  /**
   * The custom part of the tenant's users schema, in the same form representation the record
   * editor uses. Empty when the tenant only uses the core fields.
   */
  custom: RecordFormState
}

const props = defineProps<{
  open: boolean
  mode: UserEditorMode
  form: UserEditorForm
  /** The fields this tenant added to its users schema; the core fields are rendered above. */
  customFields?: FieldDefinition[]
  user?: TenantUser | null
  saving?: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  save: []
}>()

const title = computed(() =>
  props.mode === 'add'
    ? 'New user'
    : `Edit user${props.form.username ? `: ${props.form.username}` : ''}`
)

const description = computed(() =>
  props.mode === 'add'
    ? 'Create a tenant user manually. They can sign in via POST /api/auth/login.'
    : 'Update username, email, password, or any field of this tenant\u2019s users schema.'
)

const submitLabel = computed(() => (props.mode === 'add' ? 'Create user' : 'Save user'))
const submitIcon = computed(() => (props.mode === 'add' ? 'i-lucide-user-plus' : 'i-lucide-save'))
const passwordRequired = computed(() => props.mode === 'add')
const fields = computed(() => props.customFields ?? [])

function counterFor(field: FieldDefinition) {
  const value =
    field.type === 'JSON' ? props.form.custom.jsonText[field.name] : props.form.custom.values[field.name]
  return fieldCounter(field, value)
}
</script>

<template>
  <OverlayDrawer
    :open="open"
    side="right"
    width-class="w-full max-w-xl"
    labelled-by="user-editor-title"
    @update:open="emit('update:open', $event)"
  >
    <template #default="{ close }">
      <div class="flex items-start justify-between gap-3 border-b border-default p-4 sm:px-6">
        <div class="min-w-0">
          <h2 id="user-editor-title" class="font-semibold">{{ title }}</h2>
          <p class="mt-1 text-sm text-muted">{{ description }}</p>
        </div>
        <UButton
          icon="i-lucide-x"
          variant="ghost"
          color="neutral"
          aria-label="Close user editor"
          @click="close"
        />
      </div>

      <div class="flex-1 overflow-y-auto p-4 sm:p-6">
        <form id="user-editor-form" class="space-y-4" @submit.prevent="emit('save')">
          <PField label="Username" icon="i-lucide-user">
            <UInput v-model="form.username" icon="i-lucide-user" autofocus />
          </PField>

          <PField
            label="Password"
            icon="i-lucide-lock"
            :optional="!passwordRequired"
            :help="mode === 'edit' ? 'Leave blank to keep the current password.' : undefined"
          >
            <UInput
              v-model="form.password"
              type="password"
              icon="i-lucide-lock"
              autocomplete="new-password"
            />
          </PField>

          <PField label="Email" icon="i-lucide-mail" optional>
            <UInput v-model="form.email" type="email" icon="i-lucide-mail" />
          </PField>

          <PField v-if="mode === 'edit' && user" label="Role" icon="i-lucide-shield" width="md">
            <UInput :model-value="user.role" icon="i-lucide-shield" readonly />
          </PField>

          <PField
            v-for="field in fields"
            :key="field.name"
            :label="fieldDisplayName(field.name)"
            :icon="fieldTypeIcon(field.type)"
            :optional="!field.required"
            :hint="fieldConstraintHint(field)"
            :counter="counterFor(field)?.text"
            :counter-exceeded="counterFor(field)?.exceeded"
          >
            <!--
              A FILE field of the users schema is not editable here: an upload goes through the
              collection API as multipart, which this editor does not speak. It stays visible so
              the field does not silently disappear from the schema's point of view.
            -->
            <p v-if="field.type === 'FILE'" class="text-sm text-muted">
              File fields are managed through <code class="text-xs">/api/collections/users</code>.
            </p>
            <FieldValueInput
              v-else-if="field.type === 'JSON'"
              :field="field"
              v-model="form.custom.jsonText[field.name]"
            />
            <FieldValueInput v-else :field="field" v-model="form.custom.values[field.name]" />
          </PField>
        </form>
      </div>

      <div class="flex flex-col gap-2 border-t border-default p-4 sm:flex-row sm:px-6">
        <UButton
          type="submit"
          form="user-editor-form"
          class="flex-1"
          :loading="saving"
          :icon="submitIcon"
        >
          {{ submitLabel }}
        </UButton>
        <UButton variant="ghost" color="neutral" @click="close">Cancel</UButton>
      </div>
    </template>
  </OverlayDrawer>
</template>
