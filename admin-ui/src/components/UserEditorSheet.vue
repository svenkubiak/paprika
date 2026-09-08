<script setup lang="ts">
import { computed } from 'vue'
import OverlayDrawer from '@/components/OverlayDrawer.vue'
import type { TenantUser } from '@/types'

export type UserEditorMode = 'add' | 'edit'

export interface UserEditorForm {
  username: string
  password: string
  email: string
}

const props = defineProps<{
  open: boolean
  mode: UserEditorMode
  form: UserEditorForm
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
    : 'Update username, email, or password. Leave password blank to keep the current one.'
)

const submitLabel = computed(() => (props.mode === 'add' ? 'Create user' : 'Save user'))
const submitIcon = computed(() => (props.mode === 'add' ? 'i-lucide-user-plus' : 'i-lucide-save'))
const passwordRequired = computed(() => props.mode === 'add')
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
          <UFormField label="Username" required class="w-full">
            <UInput v-model="form.username" icon="i-lucide-user" class="w-full" autofocus />
          </UFormField>

          <UFormField
            label="Password"
            :required="passwordRequired"
            :help="mode === 'edit' ? 'Leave blank to keep the current password' : undefined"
            class="w-full"
          >
            <UInput
              v-model="form.password"
              type="password"
              icon="i-lucide-lock"
              class="w-full"
              autocomplete="new-password"
            />
          </UFormField>

          <UFormField label="Email" class="w-full">
            <UInput v-model="form.email" type="email" icon="i-lucide-mail" class="w-full" />
          </UFormField>

          <UFormField v-if="mode === 'edit' && user" label="Role" class="w-full">
            <UInput :model-value="user.role" icon="i-lucide-shield" class="w-full" disabled />
          </UFormField>
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
