<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import OverlayDrawer from '@/components/OverlayDrawer.vue'
import type { GlobalHookEditorMode } from '@/composables/useGlobalHookEditorSheet'
import { useBootstrap } from '@/composables/useBootstrap'
import { hookContractExample, hookRequestHeaders } from '@/lib/hook-contract'
import { selectContentProps, selectMenuUi } from '@/lib/overlay-ui'

export interface GlobalHookEditorForm {
  name: string
  url: string
  method: string
  timeoutMs: number
  secret: string
  enabled: boolean
  priority: number
  includeSchema: boolean
  failOpen: boolean
  applyToAllCollections: boolean
  targetCollections: string[]
}

const props = defineProps<{
  open: boolean
  form: GlobalHookEditorForm
  mode: GlobalHookEditorMode
  saving?: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  save: []
}>()

const { bootstrap } = useBootstrap()
const secretVisible = ref(false)

const collectionItems = computed(() =>
  (bootstrap.value?.collections || []).map((name) => ({
    label: name,
    value: name,
    icon: 'i-lucide-database'
  }))
)

const title = computed(() =>
  props.mode === 'add' ? 'Add global hook' : `Edit global hook${props.form.name ? `: ${props.form.name}` : ''}`
)

const description = computed(() =>
  props.mode === 'add'
    ? 'Configure a beforeRequest hook for collections and auth flows.'
    : 'Update global hook settings and collection scope.'
)

const contract = computed(() => hookContractExample('beforeRequest', '', props.form.includeSchema))

const requestExample = computed(() => JSON.stringify(contract.value.request, null, 2))

const responseExample = computed(() => JSON.stringify(contract.value.response, null, 2))

watch(
  () => props.open,
  (isOpen) => {
    if (isOpen) {
      secretVisible.value = false
    }
  }
)
</script>

<template>
  <OverlayDrawer
    :open="open"
    side="right"
    width-class="w-full max-w-xl"
    labelled-by="global-hook-editor-title"
    @update:open="emit('update:open', $event)"
  >
    <template #default="{ close }">
      <div class="flex items-start justify-between gap-3 border-b border-default p-4 sm:px-6">
        <div class="min-w-0">
          <h2 id="global-hook-editor-title" class="font-semibold">{{ title }}</h2>
          <p class="mt-1 text-sm text-muted">{{ description }}</p>
        </div>
        <UButton
          icon="i-lucide-x"
          variant="ghost"
          color="neutral"
          aria-label="Close global hook editor"
          @click="close"
        />
      </div>

      <div class="flex-1 overflow-y-auto p-4 sm:p-6">
        <div class="space-y-4">
          <UCard variant="subtle" :ui="{ body: 'space-y-4 p-4 sm:p-4' }">
            <UFormField label="Name" required class="w-full">
              <UInput
                v-model="form.name"
                icon="i-lucide-tag"
                placeholder="Request gate"
                class="w-full"
                autofocus
              />
            </UFormField>

            <UFormField
              label="URL"
              required
              class="w-full"
              help="HTTPS endpoint that receives the Paprika envelope"
            >
              <UInput
                v-model="form.url"
                icon="i-lucide-link"
                placeholder="https://example.com/hooks/before-request"
                class="w-full"
              />
            </UFormField>

            <UFormField
              label="Signing secret"
              required
              class="w-full"
              help="Required. Used for X-Paprika-Signature (HMAC-SHA256 over the raw JSON body)."
            >
              <UInput
                v-model="form.secret"
                :type="secretVisible ? 'text' : 'password'"
                name="global-hook-signing-secret"
                autocomplete="off"
                data-bwignore="true"
                data-1p-ignore
                data-lpignore="true"
                data-form-type="other"
                icon="i-lucide-key-round"
                placeholder="Shared secret with your receiver"
                class="w-full"
                trailing
              >
                <template #trailing>
                  <UButton
                    variant="link"
                    color="neutral"
                    size="xs"
                    :icon="secretVisible ? 'i-lucide-eye-off' : 'i-lucide-eye'"
                    :aria-label="secretVisible ? 'Hide secret' : 'Show secret'"
                    @click="secretVisible = !secretVisible"
                  />
                </template>
              </UInput>
            </UFormField>

            <div class="grid gap-4 sm:grid-cols-2">
              <UFormField label="Priority" class="w-full" help="Lower runs first">
                <UInput v-model.number="form.priority" type="number" min="1" class="w-full" />
              </UFormField>

              <UFormField label="Timeout (ms)" class="w-full">
                <UInput v-model.number="form.timeoutMs" type="number" min="100" class="w-full" />
              </UFormField>
            </div>

            <div class="space-y-3 rounded-lg border border-default bg-muted/20 p-3">
              <USwitch v-model="form.applyToAllCollections" label="Apply to all collections" />
              <p v-if="form.applyToAllCollections" class="text-xs text-muted">
                Also runs on auth flows (login, register, token refresh).
              </p>
              <UFormField
                v-else
                label="Target collections"
                required
                class="w-full"
                help="Auth flows always include all beforeRequest hooks"
              >
                <USelect
                  v-model="form.targetCollections"
                  :items="collectionItems"
                  multiple
                  placeholder="Select collections"
                  icon="i-lucide-database"
                  :content="selectContentProps"
                  :ui="selectMenuUi"
                  class="w-full font-mono"
                />
              </UFormField>
              <USwitch v-model="form.enabled" label="Enabled" />
              <USwitch v-model="form.failOpen" label="Fail open on errors" />
            </div>
          </UCard>

          <UCard variant="subtle" :ui="{ body: 'space-y-3 p-4 sm:p-4' }">
            <template #header>
              <div class="flex items-center gap-2">
                <UIcon name="i-lucide-arrow-right-left" class="size-4 text-primary" />
                <span class="font-medium">Request contract</span>
              </div>
            </template>
            <p class="text-sm text-muted">Paprika sends this JSON envelope to your endpoint:</p>
            <ul class="list-disc space-y-1 pl-5 text-xs text-muted">
              <li v-for="header in hookRequestHeaders" :key="header">{{ header }}</li>
            </ul>
            <pre class="overflow-x-auto rounded-lg bg-muted/40 p-3 font-mono text-xs">{{ requestExample }}</pre>
          </UCard>

          <UCard variant="subtle" :ui="{ body: 'space-y-3 p-4 sm:p-4' }">
            <template #header>
              <div class="flex items-center gap-2">
                <UIcon name="i-lucide-reply" class="size-4 text-primary" />
                <span class="font-medium">Expected response</span>
              </div>
            </template>
            <p v-if="contract.responseNote" class="text-sm text-muted">{{ contract.responseNote }}</p>
            <pre class="overflow-x-auto rounded-lg bg-muted/40 p-3 font-mono text-xs">{{ responseExample }}</pre>
          </UCard>
        </div>
      </div>

      <div class="flex flex-col gap-2 border-t border-default p-4 sm:flex-row sm:px-6">
        <UButton class="flex-1" :loading="saving" icon="i-lucide-save" @click="emit('save')">
          Save hook
        </UButton>
        <UButton variant="ghost" color="neutral" @click="close">Cancel</UButton>
      </div>
    </template>
  </OverlayDrawer>
</template>
