<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '@/lib/api'
import { useHookEditorSheet, emptyHookForm } from '@/composables/useHookEditorSheet'
import { useAppToast } from '@/composables/useAppToast'
import { modalUi } from '@/lib/overlay-ui'
import { hookEventLabel } from '@/lib/hook-events'
import type { HookDefinition, HookTestResult } from '@/types'

const route = useRoute()
const toast = useAppToast()
const { editorForm, editorId, saving, openEditor: openHookEditor, closeEditor, setSaving } = useHookEditorSheet()

const collection = computed(() => String(route.params.collection))
const hooks = ref<HookDefinition[]>([])
const loading = ref(true)
const hookDeleteOpen = ref(false)
const hookToDelete = ref<HookDefinition | null>(null)
const testModalOpen = ref(false)
const testingHookId = ref<string | null>(null)
const testHookName = ref('')
const testResult = ref<HookTestResult | null>(null)

const columns = [
  { accessorKey: 'name', header: 'Name' },
  { accessorKey: 'event', header: 'Event' },
  { accessorKey: 'url', header: 'URL' },
  { accessorKey: 'enabled', header: 'Enabled' },
  { accessorKey: 'priority', header: 'Priority' },
  { id: 'actions', header: '' }
]

onMounted(loadHooks)

async function loadHooks() {
  loading.value = true
  try {
    hooks.value = await api.listHooks(collection.value)
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to load hooks',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    loading.value = false
  }
}

function hookToForm(hook: HookDefinition) {
  return {
    name: hook.name,
    description: hook.description || '',
    event: hook.event,
    url: hook.url,
    method: hook.method || 'POST',
    timeoutMs: hook.timeoutMs ?? (hook.event.startsWith('before') ? 5000 : 30000),
    secret: hook.secret || '',
    enabled: hook.enabled ?? true,
    priority: hook.priority ?? 100,
    includeSchema: hook.includeSchema ?? false,
    failOpen: hook.failOpen ?? false
  }
}

function openAddHook() {
  openHookEditor({
    mode: 'add',
    form: emptyHookForm(),
    id: null,
    save: saveHook
  })
}

function openEditHook(_event: Event, tableRow: { original: HookDefinition }) {
  const hook = tableRow.original
  openHookEditor({
    mode: 'edit',
    form: hookToForm(hook),
    id: hook.id,
    save: saveHook
  })
}

function requestDeleteHook(hook: HookDefinition) {
  hookToDelete.value = hook
  hookDeleteOpen.value = true
}

async function saveHook() {
  setSaving(true)
  try {
    const payload = {
      ...editorForm.value,
      collection: collection.value
    }

    if (editorId.value) {
      await api.updateHook(collection.value, editorId.value, payload)
      toast.add({ title: 'Hook saved', color: 'success', icon: 'i-lucide-circle-check' })
    } else {
      await api.createHook(collection.value, payload)
      toast.add({ title: 'Hook created', color: 'success', icon: 'i-lucide-circle-check' })
    }

    closeEditor()
    await loadHooks()
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to save hook',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    setSaving(false)
  }
}

async function deleteHook() {
  const id = hookToDelete.value?.id
  if (!id) return

  setSaving(true)
  try {
    await api.deleteHook(collection.value, id)
    hookDeleteOpen.value = false
    hookToDelete.value = null
    await loadHooks()
    toast.add({ title: 'Hook deleted', color: 'success', icon: 'i-lucide-circle-check' })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to delete hook',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    setSaving(false)
  }
}

async function confirmDeleteHook() {
  await deleteHook()
}

async function runTableTest(hook: HookDefinition, event: Event) {
  event.stopPropagation()

  if (!hook.url?.trim() || !hook.secret?.trim()) {
    toast.add({
      title: 'Hook needs a URL and signing secret before testing',
      color: 'warning',
      icon: 'i-lucide-triangle-alert'
    })
    return
  }

  testingHookId.value = hook.id
  testHookName.value = hook.name
  testResult.value = null
  testModalOpen.value = true

  try {
    testResult.value = await api.testHook(collection.value, {
      ...hookToForm(hook),
      id: hook.id
    })
  } catch (error) {
    testResult.value = {
      deliveryId: null,
      requestPayload: null,
      signature: null,
      statusCode: 0,
      responseBody: null,
      latencyMs: 0,
      error: error instanceof Error ? error.message : 'Test failed'
    }
  } finally {
    testingHookId.value = null
  }
}

const deleteHookName = computed(() => hookToDelete.value?.name ?? 'this hook')

const testSucceeded = computed(() => testResult.value !== null && !testResult.value.error)
</script>

<template>
  <div class="space-y-6">
    <UAlert
      color="info"
      variant="soft"
      icon="i-lucide-webhook"
      title="HTTP hooks"
      description="Configure tenant-specific HTTP endpoints that run before or after CRUD operations. Blocking hooks can mutate request data; async hooks run after the operation succeeds."
    />

    <UCard :ui="{ body: 'p-0 sm:p-0' }">
      <template #header>
        <div class="flex items-center justify-between gap-3">
          <div class="flex items-center gap-2">
            <UIcon name="i-lucide-webhook" class="size-5 text-primary" />
            <h2 class="font-semibold">Hooks for {{ collection }}</h2>
          </div>
          <UButton icon="i-lucide-plus" @click="openAddHook">Add hook</UButton>
        </div>
      </template>

      <UTable :data="hooks" :columns="columns" :loading="loading" @select="openEditHook">
        <template #name-cell="{ row }">
          <div>
            <span class="font-medium">{{ row.original.name }}</span>
            <p v-if="row.original.description" class="mt-0.5 text-xs text-muted">{{ row.original.description }}</p>
          </div>
        </template>
        <template #event-cell="{ row }">
          <UBadge variant="soft" color="primary">{{ hookEventLabel(row.original.event) }}</UBadge>
        </template>
        <template #url-cell="{ row }">
          <code class="text-xs">{{ row.original.url }}</code>
        </template>
        <template #enabled-cell="{ row }">
          <UBadge :color="row.original.enabled !== false ? 'success' : 'neutral'" variant="soft">
            {{ row.original.enabled !== false ? 'yes' : 'no' }}
          </UBadge>
        </template>
        <template #priority-cell="{ row }">
          <span class="font-mono text-sm">{{ row.original.priority ?? 100 }}</span>
        </template>
        <template #actions-cell="{ row }">
          <div class="flex justify-end gap-2" @click.stop>
            <UButton
              size="sm"
              variant="soft"
              color="neutral"
              icon="i-lucide-flask-conical"
              :loading="testingHookId === row.original.id"
              @click="runTableTest(row.original, $event)"
            >
              Test
            </UButton>
            <UButton
              size="sm"
              color="error"
              variant="soft"
              icon="i-lucide-trash-2"
              @click="requestDeleteHook(row.original)"
            >
              Delete
            </UButton>
          </div>
        </template>
      </UTable>
    </UCard>

    <UModal v-model:open="hookDeleteOpen" portal="body" :ui="modalUi">
      <template #content>
        <UCard>
          <template #header>
            <div class="flex items-center gap-2">
              <UIcon name="i-lucide-triangle-alert" class="size-5 text-error" />
              <h3 class="font-semibold">Delete hook</h3>
            </div>
          </template>
          <p class="text-sm text-muted">
            Delete hook "{{ deleteHookName }}" permanently?
          </p>
          <template #footer>
            <div class="flex justify-end gap-2">
              <UButton variant="ghost" color="neutral" @click="hookDeleteOpen = false">Cancel</UButton>
              <UButton color="error" :loading="saving" icon="i-lucide-trash-2" @click="confirmDeleteHook">
                Delete hook
              </UButton>
            </div>
          </template>
        </UCard>
      </template>
    </UModal>

    <UModal v-model:open="testModalOpen" portal="body" :ui="modalUi">
      <template #content>
        <UCard>
          <template #header>
            <div class="flex items-center justify-between gap-3">
              <div class="flex items-center gap-2">
                <UIcon name="i-lucide-flask-conical" class="size-5 text-primary" />
                <h3 class="font-semibold">Test: {{ testHookName }}</h3>
              </div>
              <UBadge
                v-if="testResult"
                variant="soft"
                :color="testSucceeded ? 'success' : 'error'"
              >
                {{ testSucceeded ? `Success · ${testResult.latencyMs} ms` : 'Failed' }}
              </UBadge>
              <UBadge v-else variant="soft" color="neutral">Running…</UBadge>
            </div>
          </template>

          <div v-if="!testResult" class="flex items-center justify-center gap-2 py-8 text-sm text-muted">
            <UIcon name="i-lucide-loader-circle" class="size-4 animate-spin" />
            Sending sample payload…
          </div>

          <div v-else class="space-y-4">
            <UAlert
              v-if="testResult.error"
              color="error"
              variant="soft"
              icon="i-lucide-circle-x"
              :title="testResult.error"
            />
            <template v-else>
              <dl class="grid gap-2 text-sm sm:grid-cols-2">
                <div>
                  <dt class="text-muted">HTTP status</dt>
                  <dd class="font-mono">{{ testResult.statusCode }}</dd>
                </div>
                <div>
                  <dt class="text-muted">Latency</dt>
                  <dd class="font-mono">{{ testResult.latencyMs }} ms</dd>
                </div>
                <div v-if="testResult.signature" class="sm:col-span-2">
                  <dt class="text-muted">Signature</dt>
                  <dd class="break-all font-mono text-xs">{{ testResult.signature }}</dd>
                </div>
              </dl>
              <div>
                <p class="mb-2 text-sm font-medium">Response body</p>
                <pre
                  v-if="testResult.responseBody"
                  class="max-h-64 overflow-auto rounded-lg bg-muted/40 p-3 font-mono text-xs"
                >{{ testResult.responseBody }}</pre>
                <p v-else class="text-sm text-muted">Empty response body.</p>
              </div>
            </template>
          </div>

          <template #footer>
            <div class="flex justify-end">
              <UButton variant="ghost" color="neutral" @click="testModalOpen = false">Close</UButton>
            </div>
          </template>
        </UCard>
      </template>
    </UModal>
  </div>
</template>
