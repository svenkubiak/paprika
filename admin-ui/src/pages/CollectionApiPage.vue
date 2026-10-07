<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouteCollection } from '@/composables/useRouteCollection'
import { api } from '@/lib/api'
import {
  authEndpointDocs,
  buildCollectionApiDocs,
  type ApiEndpointDoc,
  type ApiExampleBlock
} from '@/lib/collection-api-docs'
import { ruleLevelChoice, type RuleKey } from '@/lib/rule-levels'
import { copyToClipboard } from '@/lib/utils'
import { useAppToast } from '@/composables/useAppToast'
import ApiEndpointRow from '@/components/ApiEndpointRow.vue'
import type { CollectionDefinition } from '@/types'

const REALTIME_DOCS_URL = 'https://docs.getpaprika.dev/admin-ui/collection-api#realtime-sse'

const toast = useAppToast()

const collection = useRouteCollection()
const isUsers = computed(() => collection.value === 'users')
const definition = ref<CollectionDefinition | null>(null)

const baseUrl = computed(() => `${window.location.origin}/api/collections/${collection.value}`)
const rulesLink = computed(() => `/admin/collections/${collection.value}/rules`)

const endpoints = computed<ApiEndpointDoc[]>(() =>
  buildCollectionApiDocs(collection.value, definition.value?.fields || [])
)
// Sign-up, login and the recovery flows act on user accounts, so they belong to this collection
// only rather than being repeated on every other one.
const authEndpoints = computed(() => (isUsers.value ? authEndpointDocs : []))

// File downloads read the parent record and file deletes change it, so they follow those rules.
function ruleKeyOf(endpoint: ApiEndpointDoc): RuleKey | null {
  if (endpoint.id === 'list') return 'listRule'
  if (endpoint.id === 'read' || endpoint.id.startsWith('file-download-')) return 'viewRule'
  if (endpoint.id === 'create') return 'createRule'
  if (endpoint.id === 'update' || endpoint.id.startsWith('file-delete-')) return 'updateRule'
  if (endpoint.id === 'delete') return 'deleteRule'
  return null
}

function accessOf(endpoint: ApiEndpointDoc) {
  const key = ruleKeyOf(endpoint)
  return key ? ruleLevelChoice(definition.value?.rules?.[key], isUsers.value) : undefined
}

/** Every error response of the page once, ordered by status, instead of repeated per endpoint. */
const errors = computed(() => {
  const seen = new Map<string, ApiExampleBlock>()
  for (const endpoint of [...endpoints.value, ...authEndpoints.value]) {
    for (const example of endpoint.examples) {
      if (example.variant !== 'error') continue
      const key = `${example.title}|${example.description ?? ''}`
      if (!seen.has(key)) seen.set(key, example)
    }
  }
  return [...seen.values()].sort((a, b) => a.title.localeCompare(b.title, undefined, { numeric: true }))
})

async function loadDefinition() {
  try {
    definition.value = await api.getCollectionDefinition(collection.value)
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to load collection',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  }
}

// Also reload on a collection change: navigating between collections reuses this component.
watch(collection, loadDefinition)

async function copy(text: string) {
  await copyToClipboard(text)
  toast.add({ title: 'Copied to clipboard', color: 'success', icon: 'i-lucide-copy' })
}

// Awaited in setup, so the <Suspense> in App.vue keeps the previous tab on screen until this one
// has its data instead of flashing the empty state first.
await loadDefinition()
</script>

<template>
  <div class="space-y-6">
    <UCard>
      <dl class="grid gap-4 text-sm sm:grid-cols-[10rem_1fr]">
        <dt class="font-medium text-default">Base URL</dt>
        <dd class="flex min-w-0 items-center gap-1">
          <code class="min-w-0 truncate">{{ baseUrl }}</code>
          <CopyButton :value="baseUrl" label="Copy base URL" size="xs" />
        </dd>

        <dt class="font-medium text-default">Authentication</dt>
        <dd class="space-y-1">
          <code>Authorization: Bearer &lt;token&gt;</code>
          <p class="text-muted">
            An access token from <code>POST /api/auth/login</code> or an API key. Not needed where an
            endpoint is <span class="font-medium text-default">Public</span>; the badge on each endpoint
            shows its rule.
          </p>
        </dd>

        <dt class="font-medium text-default">System fields</dt>
        <dd class="text-muted">
          Every record has <code>id</code>, <code>createdAt</code> and <code>updatedAt</code>. Paprika
          sets them; clients cannot write them.
        </dd>

        <template v-if="!isUsers">
          <dt class="font-medium text-default">Realtime</dt>
          <dd class="text-muted">
            Changes can be streamed with Server-Sent Events, see the
            <a
              :href="REALTIME_DOCS_URL"
              target="_blank"
              rel="noopener noreferrer"
              class="text-readable-primary hover:underline"
            >documentation</a>.
          </dd>
        </template>
      </dl>
    </UCard>

    <UCard :ui="{ body: 'p-0 sm:p-0' }">
      <template #header>
        <h2 class="font-semibold">Endpoints</h2>
      </template>
      <div class="divide-y divide-default">
        <ApiEndpointRow
          v-for="endpoint in endpoints"
          :key="`${endpoint.method}-${endpoint.id}`"
          :endpoint="endpoint"
          :access="accessOf(endpoint)"
          :rules-link="rulesLink"
          @copy="copy"
        />
      </div>
    </UCard>

    <UCard v-if="authEndpoints.length" :ui="{ body: 'p-0 sm:p-0' }">
      <template #header>
        <h2 class="font-semibold">Authentication</h2>
        <p class="mt-1 text-sm text-muted">
          Sign-up, sign-in and account recovery for the users of this tenant. These endpoints are
          not governed by the rules of this collection.
        </p>
      </template>
      <div class="divide-y divide-default">
        <ApiEndpointRow
          v-for="endpoint in authEndpoints"
          :key="`auth-${endpoint.id}`"
          :endpoint="endpoint"
          @copy="copy"
        />
      </div>
    </UCard>

    <UCard :ui="{ body: 'p-0 sm:p-0' }">
      <template #header>
        <h2 class="font-semibold">Errors</h2>
        <p class="mt-1 text-sm text-muted">
          Errors come as <code>{ "error": "…" }</code>, schema validation errors as a list of
          <code>{ "field", "message" }</code>.
        </p>
      </template>
      <table class="w-full text-sm">
        <tbody class="divide-y divide-default">
          <tr v-for="error in errors" :key="`${error.title}-${error.description}`">
            <td class="w-44 whitespace-nowrap px-4 py-2.5 font-mono text-xs sm:px-6">{{ error.title }}</td>
            <td class="px-4 py-2.5 text-toned sm:pr-6">{{ error.description }}</td>
          </tr>
        </tbody>
      </table>
    </UCard>
  </div>
</template>
