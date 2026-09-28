<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import CopyButton from '@/components/CopyButton.vue'
import OverlayDrawer from '@/components/OverlayDrawer.vue'
import PField from '@/components/PField.vue'
import PSwitchField from '@/components/PSwitchField.vue'
import type { GlobalHookEditorMode } from '@/composables/useGlobalHookEditorSheet'
import { useBootstrap } from '@/composables/useBootstrap'
import { hookContractExample, hookRequestHeaders } from '@/lib/hook-contract'
import { selectContentProps, selectMenuUi } from '@/lib/overlay-ui'

export interface GlobalHookEditorForm {
  name: string
  description: string
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
  /** Comma-separated header names, sent to the backend as a list. */
  forwardHeaders: string
  includeFileRoutes: boolean
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

/**
 * Kept out of the template because the route contains braces, which the template would read as an
 * interpolation.
 */
const fileRoutesDetails =
  'The hook then runs on /api/collections/{collection}/{id}/files/{field} as well - one hook ' +
  'roundtrip per file request. An image list fires many of them at once, so the hook needs its ' +
  "own cache and a timeout below Paprika's 5-second budget for blocking hooks."


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
            <PField label="Name" icon="i-lucide-tag">
              <UInput v-model="form.name" icon="i-lucide-tag" placeholder="Request gate" autofocus />
            </PField>

            <PField label="Description" icon="i-lucide-align-left" optional>
              <UTextarea v-model="form.description" placeholder="What does this hook do?" :rows="2" class="w-full" />
            </PField>

            <PField
              label="URL"
              icon="i-lucide-link"
              help="HTTPS endpoint that receives the Paprika envelope."
            >
              <UInput
                v-model="form.url"
                icon="i-lucide-link"
                placeholder="https://example.com/hooks/before-request"
              />
            </PField>

            <PField
              label="Signing secret"
              icon="i-lucide-key-round"
              help="Used for X-Paprika-Signature: HMAC-SHA256 over the raw JSON body."
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
                trailing
              >
                <template #trailing>
                  <UButton
                    variant="ghost"
                    color="neutral"
                    size="xs"
                    :icon="secretVisible ? 'i-lucide-eye-off' : 'i-lucide-eye'"
                    :aria-label="secretVisible ? 'Hide secret' : 'Show secret'"
                    @click="secretVisible = !secretVisible"
                  />
                  <CopyButton size="xs" :value="form.secret" label="Copy signing secret" />
                </template>
              </UInput>
            </PField>

            <div class="grid gap-4 sm:grid-cols-2">
              <PField label="Priority" icon="i-lucide-list-ordered" help="Lower runs first.">
                <UInput v-model.number="form.priority" type="number" min="1" class="font-mono" />
              </PField>

              <PField label="Timeout" icon="i-lucide-clock" help="How long the endpoint may take to answer.">
                <UInput v-model.number="form.timeoutMs" type="number" min="100" class="font-mono">
                  <template #trailing>
                    <span class="text-xs text-muted">ms</span>
                  </template>
                </UInput>
              </PField>
            </div>

            <PField
              label="Forward request headers"
              icon="i-lucide-list"
              optional
              help="Incoming request headers to send along, comma separated."
              details="They are sent on top of the default Content-Type, User-Agent, Accept, Accept-Language and X-Request-Id. Only list what the target actually needs to decide - Authorization, Cookie, Set-Cookie and Proxy-Authorization are never forwarded and are rejected here."
            >
              <UInput
                v-model="form.forwardHeaders"
                icon="i-lucide-list"
                placeholder="x-app-key-id, x-signature"
                class="font-mono"
              />
            </PField>

            <div class="space-y-2">
              <PSwitchField
                v-model="form.applyToAllCollections"
                label="Apply to all collections"
                icon="i-lucide-layers"
                help="Also runs on auth flows (login, register, token refresh)."
              />
              <PField
                v-if="!form.applyToAllCollections"
                label="Target collections"
                icon="i-lucide-database"
                help="Auth flows always include all beforeRequest hooks."
              >
                <USelect
                  v-model="form.targetCollections"
                  :items="collectionItems"
                  multiple
                  placeholder="Select collections"
                  icon="i-lucide-database"
                  :content="selectContentProps"
                  :ui="selectMenuUi"
                  class="font-mono"
                />
              </PField>
              <PSwitchField
                v-model="form.enabled"
                label="Enabled"
                icon="i-lucide-radio"
                help="Off keeps the configuration without calling the endpoint."
              />
              <PSwitchField
                v-model="form.failOpen"
                label="Fail open on errors"
                icon="i-lucide-shield-off"
                help="Carry on with the request when the hook fails or answers with a non-2xx status."
                details="With this off, a hook that is unreachable, times out or answers with an error rejects the request with 502."
              />
              <PSwitchField
                v-model="form.includeFileRoutes"
                label="Also guard file routes"
                icon="i-lucide-paperclip"
                help="Off by default. When on, the hook also runs before file downloads and deletions."
                :details="fileRoutesDetails"
              />
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
