<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import OverlayDrawer from '@/components/OverlayDrawer.vue'
import { normalizeSlug } from '@/lib/utils'
import type { TenantDefinition } from '@/types'

export type TenantEditorMode = 'add' | 'edit'

export interface TenantEditorForm {
  name: string
  slug: string
  registrationEnabled: boolean
  passwordResetEnabled: boolean
  emailVerificationEnabled: boolean
  emailVerificationRequired: boolean
  passwordResetUrl: string
  emailVerificationUrl: string
}

const props = defineProps<{
  open: boolean
  mode: TenantEditorMode
  form: TenantEditorForm
  tenant?: TenantDefinition | null
  saving?: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  save: []
}>()

const title = computed(() =>
  props.mode === 'add' ? 'New tenant' : `Edit tenant${props.form.name ? `: ${props.form.name}` : ''}`
)

const description = computed(() =>
  props.mode === 'add'
    ? 'Each tenant gets its own database with isolated collections and users.'
    : 'Update tenant details and self-registration settings.'
)

const submitLabel = computed(() => (props.mode === 'add' ? 'Create tenant' : 'Save tenant'))
const submitIcon = computed(() => (props.mode === 'add' ? 'i-lucide-plus' : 'i-lucide-save'))

const slugManual = ref(false)

watch(
  () => props.open,
  (isOpen) => {
    if (isOpen) {
      slugManual.value = props.mode === 'edit'
    }
  }
)

watch(
  () => props.form.name,
  (name) => {
    if (!slugManual.value) {
      props.form.slug = normalizeSlug(name)
    }
  }
)

watch(
  () => props.form.emailVerificationEnabled,
  (enabled) => {
    if (!enabled) {
      props.form.emailVerificationRequired = false
    }
  }
)

function onSlugInput() {
  slugManual.value = true
}
</script>

<template>
  <OverlayDrawer
    :open="open"
    side="right"
    width-class="w-full max-w-xl"
    labelled-by="tenant-editor-title"
    @update:open="emit('update:open', $event)"
  >
    <template #default="{ close }">
      <div class="flex items-start justify-between gap-3 border-b border-default p-4 sm:px-6">
        <div class="min-w-0">
          <h2 id="tenant-editor-title" class="font-semibold">{{ title }}</h2>
          <p class="mt-1 text-sm text-muted">{{ description }}</p>
        </div>
        <UButton
          icon="i-lucide-x"
          variant="ghost"
          color="neutral"
          aria-label="Close tenant editor"
          @click="close"
        />
      </div>

      <div class="flex-1 overflow-y-auto p-4 sm:p-6">
        <form id="tenant-editor-form" class="space-y-4" @submit.prevent="emit('save')">
          <UFormField label="Name" required class="w-full">
            <UInput
              v-model="form.name"
              icon="i-lucide-building-2"
              class="w-full"
              autofocus
            />
          </UFormField>

          <UFormField
            label="Slug"
            required
            help="Lowercase letters, numbers, and hyphens only"
            class="w-full"
          >
            <UInput v-model="form.slug" icon="i-lucide-link" class="w-full" @input="onSlugInput" />
          </UFormField>

          <template v-if="mode === 'edit' && tenant">
            <UFormField label="Database" class="w-full">
              <UInput :model-value="tenant.databaseName" icon="i-lucide-database" class="w-full" disabled />
            </UFormField>

            <UFormField label="Status" class="w-full">
              <UInput :model-value="tenant.status" icon="i-lucide-activity" class="w-full" disabled />
            </UFormField>

            <UCard variant="subtle" :ui="{ body: 'p-4 sm:p-4' }">
              <div class="flex items-start justify-between gap-4">
                <div>
                  <p class="font-medium">Self-registration</p>
                  <p class="mt-1 text-sm text-muted">
                    Allow users to register via POST /api/auth/register with this tenant slug.
                  </p>
                </div>
                <USwitch v-model="form.registrationEnabled" />
              </div>
            </UCard>

            <UCard variant="subtle" :ui="{ body: 'p-4 sm:p-4' }">
              <div class="flex items-start justify-between gap-4">
                <div>
                  <p class="font-medium">Password reset</p>
                  <p class="mt-1 text-sm text-muted">
                    Enable POST /api/auth/password/forgot and /reset. Paprika emails the reset link
                    over the instance SMTP settings.
                  </p>
                </div>
                <USwitch v-model="form.passwordResetEnabled" />
              </div>
              <UFormField
                v-if="form.passwordResetEnabled"
                label="Reset link URL"
                help="Your app's reset page. Paprika appends ?token=…, or substitutes a {token} placeholder."
                class="mt-3 w-full"
              >
                <UInput
                  v-model="form.passwordResetUrl"
                  icon="i-lucide-link"
                  class="w-full"
                  placeholder="https://app.example.com/reset"
                />
              </UFormField>
            </UCard>

            <UCard variant="subtle" :ui="{ body: 'p-4 sm:p-4' }">
              <div class="flex items-start justify-between gap-4">
                <div>
                  <p class="font-medium">Email verification</p>
                  <p class="mt-1 text-sm text-muted">
                    Enable POST /api/auth/verify/request and /confirm. Paprika emails the verification
                    link. Confirming sets the user's emailVerified flag; it only gates login if
                    required below.
                  </p>
                </div>
                <USwitch v-model="form.emailVerificationEnabled" />
              </div>
              <UFormField
                v-if="form.emailVerificationEnabled"
                label="Verification link URL"
                help="Your app's verification page. Paprika appends ?token=…, or substitutes a {token} placeholder."
                class="mt-3 w-full"
              >
                <UInput
                  v-model="form.emailVerificationUrl"
                  icon="i-lucide-link"
                  class="w-full"
                  placeholder="https://app.example.com/verify"
                />
              </UFormField>
              <div
                v-if="form.emailVerificationEnabled"
                class="mt-3 flex items-start justify-between gap-4 border-t border-default pt-3"
              >
                <div>
                  <p class="text-sm font-medium">Require for login</p>
                  <p class="mt-1 text-sm text-muted">
                    Block login for users whose email isn't verified yet.
                  </p>
                </div>
                <USwitch v-model="form.emailVerificationRequired" />
              </div>
            </UCard>
          </template>
        </form>
      </div>

      <div class="flex flex-col gap-2 border-t border-default p-4 sm:flex-row sm:px-6">
        <UButton
          type="submit"
          form="tenant-editor-form"
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
