<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import CopyButton from '@/components/CopyButton.vue'
import OverlayDrawer from '@/components/OverlayDrawer.vue'
import PField from '@/components/PField.vue'
import { normalizeSlug } from '@/lib/utils'
import type { TenantDefinition } from '@/types'

export type TenantEditorMode = 'add' | 'edit'

export interface TenantEditorForm {
  name: string
  slug: string
  webhookAllowlist: string
  tokenIssuers: string
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
    : 'Update the tenant name and slug.'
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
          <PField label="Name" icon="i-lucide-building-2">
            <UInput v-model="form.name" icon="i-lucide-building-2" autofocus />
          </PField>

          <PField
            label="Slug"
            icon="i-lucide-link"
            width="md"
            help="Lowercase letters, numbers, and hyphens only."
          >
            <UInput v-model="form.slug" icon="i-lucide-link" class="font-mono" @input="onSlugInput" />
          </PField>

          <template v-if="mode === 'edit' && tenant">
            <!-- Read-only rather than disabled, so the name can be copied into a mongo shell. -->
            <PField label="Database" icon="i-lucide-database" width="md">
              <UInput :model-value="tenant.databaseName" icon="i-lucide-database" readonly class="font-mono">
                <template #trailing>
                  <CopyButton size="xs" :value="tenant.databaseName" label="Copy database name" />
                </template>
              </UInput>
            </PField>

            <PField
              label="Webhook allowlist"
              icon="i-lucide-shield-check"
              optional
              help="Host:port combinations this tenant's hooks may target, separated by commas."
              details="Loopback and private-network addresses are blocked as webhook targets by default. Listing one here lifts that block for this tenant only."
            >
              <UInput
                v-model="form.webhookAllowlist"
                icon="i-lucide-shield-check"
                class="font-mono"
                placeholder="127.0.0.1:8092, 192.168.1.10:9000"
              />
            </PField>

            <PField
              label="Token issuers"
              icon="i-lucide-key-round"
              optional
              help="User IDs that may mint a session for any other user of this tenant. Empty means nobody can."
              details="These users may call POST /api/auth/issue-token without a password. It is the strongest permission below superadmin, intended for a trusted backend that authenticated the user elsewhere. Separate multiple IDs with commas."
            >
              <UInput
                v-model="form.tokenIssuers"
                icon="i-lucide-key-round"
                class="font-mono"
                placeholder="user id, user id"
              />
            </PField>
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
