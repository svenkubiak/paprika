<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '@/lib/api'
import {
  authEndpointDocs,
  buildCollectionApiDocs,
  methodColor,
  realtimeEndpointDocs,
  type ApiEndpointDoc
} from '@/lib/collection-api-docs'
import { copyToClipboard } from '@/lib/utils'
import { useAppToast } from '@/composables/useAppToast'
import ApiEndpointExamples from '@/components/ApiEndpointExamples.vue'
import type { CollectionDefinition } from '@/types'

const route = useRoute()
const toast = useAppToast()

const collection = computed(() => String(route.params.collection))
const isUsers = computed(() => collection.value === 'users')
const definition = ref<CollectionDefinition | null>(null)
const loading = ref(true)
const openEndpoints = ref<Record<string, boolean>>({})
const openAuthEndpoints = ref<Record<string, boolean>>({})
const openRealtimeEndpoints = ref<Record<string, boolean>>({})

const endpoints = computed<ApiEndpointDoc[]>(() =>
  buildCollectionApiDocs(collection.value, definition.value?.fields || [])
)

onMounted(async () => {
  loading.value = true
  try {
    definition.value = await api.getCollectionDefinition(collection.value)
  } finally {
    loading.value = false
  }
})

function endpointKey(endpoint: ApiEndpointDoc) {
  return `${endpoint.method}-${endpoint.id}`
}

function isOpen(endpoint: ApiEndpointDoc) {
  return openEndpoints.value[endpointKey(endpoint)] ?? false
}

function setOpen(endpoint: ApiEndpointDoc, open: boolean) {
  openEndpoints.value[endpointKey(endpoint)] = open
}

function authEndpointKey(endpoint: ApiEndpointDoc) {
  return `auth-${endpoint.id}`
}

function isAuthOpen(endpoint: ApiEndpointDoc) {
  return openAuthEndpoints.value[authEndpointKey(endpoint)] ?? false
}

function setAuthOpen(endpoint: ApiEndpointDoc, open: boolean) {
  openAuthEndpoints.value[authEndpointKey(endpoint)] = open
}

function realtimeEndpointKey(endpoint: ApiEndpointDoc) {
  return `realtime-${endpoint.id}`
}

function isRealtimeOpen(endpoint: ApiEndpointDoc) {
  return openRealtimeEndpoints.value[realtimeEndpointKey(endpoint)] ?? false
}

function setRealtimeOpen(endpoint: ApiEndpointDoc, open: boolean) {
  openRealtimeEndpoints.value[realtimeEndpointKey(endpoint)] = open
}

async function copy(text: string) {
  await copyToClipboard(text)
  toast.add({ title: 'Copied to clipboard', color: 'success', icon: 'i-lucide-copy' })
}

function copyEndpoint(endpoint: ApiEndpointDoc) {
  return copy(`${endpoint.method} ${endpoint.path}`)
}
</script>

<template>
  <div class="space-y-6">
    <UAlert
      color="info"
      variant="soft"
      icon="i-lucide-code"
      title="REST API"
      :description="`HTTP endpoints for the ${collection} collection. Expand an endpoint to see request and response examples.`"
    />

    <UAlert
      color="neutral"
      variant="soft"
      icon="i-lucide-layers"
      title="System fields"
      description="Every record includes id, createdAt, and updatedAt. Paprika sets these automatically — they cannot be defined in the schema or written by clients."
    />

    <UCard :ui="{ body: 'p-0 sm:p-0' }">
      <template #header>
        <div class="flex items-center gap-2">
          <UIcon name="i-lucide-plug" class="size-5 text-primary" />
          <h2 class="font-semibold">Endpoints</h2>
        </div>
      </template>

      <div v-if="loading" class="px-4 py-8 text-center text-sm text-muted sm:px-6">
        Loading schema…
      </div>

      <div v-else class="divide-y divide-default">
        <div v-for="endpoint in endpoints" :key="endpointKey(endpoint)">
          <UCollapsible
            :open="isOpen(endpoint)"
            :unmount-on-hide="false"
            class="w-full"
            @update:open="setOpen(endpoint, $event)"
          >
            <button
              type="button"
              class="flex w-full items-center gap-3 px-4 py-4 text-left transition-colors hover:bg-muted/30 sm:px-6"
            >
              <UBadge :color="methodColor(endpoint.method)" variant="soft" class="shrink-0">
                {{ endpoint.method }}
              </UBadge>

              <code class="min-w-0 flex-1 text-sm">{{ endpoint.path }}</code>

              <div class="flex shrink-0 items-center gap-1">
                <UButton
                  size="xs"
                  variant="ghost"
                  color="neutral"
                  icon="i-lucide-copy"
                  aria-label="Copy endpoint"
                  @click.stop="copyEndpoint(endpoint)"
                />
                <UIcon
                  :name="isOpen(endpoint) ? 'i-lucide-chevron-up' : 'i-lucide-chevron-down'"
                  class="size-4 text-muted"
                />
              </div>
            </button>

            <template #content>
              <div class="space-y-4 border-t border-default bg-muted/15 px-4 py-4 sm:px-6">
                <UAlert
                  color="info"
                  variant="soft"
                  icon="i-lucide-info"
                  :description="endpoint.summary"
                />

                <ApiEndpointExamples :examples="endpoint.examples" @copy="copy" />
              </div>
            </template>
          </UCollapsible>
        </div>
      </div>
    </UCard>

    <UCard :ui="{ body: 'p-0 sm:p-0' }">
      <template #header>
        <div class="flex items-center gap-2">
          <UIcon name="i-lucide-key-round" class="size-5 text-primary" />
          <h2 class="font-semibold">Authentication</h2>
        </div>
      </template>

      <div class="border-b border-default px-4 py-4 sm:px-6">
        <UAlert
          color="info"
          variant="soft"
          icon="i-lucide-shield-check"
          description="Collection endpoints require Authorization: Bearer <accessToken> unless collection rules allow public access."
        />
      </div>

      <div class="divide-y divide-default">
        <div v-for="endpoint in authEndpointDocs" :key="authEndpointKey(endpoint)">
          <UCollapsible
            :open="isAuthOpen(endpoint)"
            :unmount-on-hide="false"
            class="w-full"
            @update:open="setAuthOpen(endpoint, $event)"
          >
            <button
              type="button"
              class="flex w-full items-center gap-3 px-4 py-4 text-left transition-colors hover:bg-muted/30 sm:px-6"
            >
              <UBadge :color="methodColor(endpoint.method)" variant="soft" class="shrink-0">
                {{ endpoint.method }}
              </UBadge>

              <code class="min-w-0 flex-1 text-sm">{{ endpoint.path }}</code>

              <div class="flex shrink-0 items-center gap-1">
                <UButton
                  size="xs"
                  variant="ghost"
                  color="neutral"
                  icon="i-lucide-copy"
                  aria-label="Copy endpoint"
                  @click.stop="copyEndpoint(endpoint)"
                />
                <UIcon
                  :name="isAuthOpen(endpoint) ? 'i-lucide-chevron-up' : 'i-lucide-chevron-down'"
                  class="size-4 text-muted"
                />
              </div>
            </button>

            <template #content>
              <div class="space-y-4 border-t border-default bg-muted/15 px-4 py-4 sm:px-6">
                <UAlert
                  color="info"
                  variant="soft"
                  icon="i-lucide-info"
                  :description="endpoint.summary"
                />

                <ApiEndpointExamples :examples="endpoint.examples" @copy="copy" />
              </div>
            </template>
          </UCollapsible>
        </div>
      </div>
    </UCard>

    <UCard v-if="!isUsers" :ui="{ body: 'p-0 sm:p-0' }">
      <template #header>
        <div class="flex items-center gap-2">
          <UIcon name="i-lucide-radio" class="size-5 text-primary" />
          <h2 class="font-semibold">Realtime (SSE)</h2>
        </div>
      </template>

      <div class="border-b border-default px-4 py-4 sm:px-6">
        <UAlert
          color="info"
          variant="soft"
          icon="i-lucide-info"
          description="Connect, then POST /api/realtime/subscribe with the clientId from the connect event. A subscribed event confirms success. Record events use the collection name as the SSE event type. viewRule must allow the subscriber or nothing is sent."
        />
      </div>

      <div class="divide-y divide-default">
        <div v-for="endpoint in realtimeEndpointDocs" :key="realtimeEndpointKey(endpoint)">
          <UCollapsible
            :open="isRealtimeOpen(endpoint)"
            :unmount-on-hide="false"
            class="w-full"
            @update:open="setRealtimeOpen(endpoint, $event)"
          >
            <button
              type="button"
              class="flex w-full items-center gap-3 px-4 py-4 text-left transition-colors hover:bg-muted/30 sm:px-6"
            >
              <UBadge :color="methodColor(endpoint.method)" variant="soft" class="shrink-0">
                {{ endpoint.method }}
              </UBadge>

              <code class="min-w-0 flex-1 text-sm">{{ endpoint.path }}</code>

              <div class="flex shrink-0 items-center gap-1">
                <UButton
                  size="xs"
                  variant="ghost"
                  color="neutral"
                  icon="i-lucide-copy"
                  aria-label="Copy endpoint"
                  @click.stop="copyEndpoint(endpoint)"
                />
                <UIcon
                  :name="isRealtimeOpen(endpoint) ? 'i-lucide-chevron-up' : 'i-lucide-chevron-down'"
                  class="size-4 text-muted"
                />
              </div>
            </button>

            <template #content>
              <div class="space-y-4 border-t border-default bg-muted/15 px-4 py-4 sm:px-6">
                <UAlert
                  color="info"
                  variant="soft"
                  icon="i-lucide-info"
                  :description="endpoint.summary"
                />

                <ApiEndpointExamples :examples="endpoint.examples" @copy="copy" />
              </div>
            </template>
          </UCollapsible>
        </div>
      </div>
    </UCard>
  </div>
</template>
