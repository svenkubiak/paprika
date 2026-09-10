<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '@/lib/api'
import { useBootstrap } from '@/composables/useBootstrap'
import {
  useGlobalHookEditorSheet,
  emptyGlobalHookForm
} from '@/composables/useGlobalHookEditorSheet'
import { useAppToast } from '@/composables/useAppToast'
import { modalUi } from '@/lib/overlay-ui'
import type { HookDefinition, HookTestResult } from '@/types'

const toast = useAppToast()
const { bootstrap, load } = useBootstrap()
const {
  editorForm,
  editorId,
  openEditor,
  closeEditor,
  setSaving
} = useGlobalHookEditorSheet()

const hooks = ref<HookDefinition[]>([])
const loading = ref(true)
const deleteOpen = ref(false)
const hookToDelete = ref<HookDefinition | null>(null)
const testModalOpen = ref(false)
const testResult = ref<HookTestResult | null>(null)
const testingHookId = ref<string | null>(null)

const columns = [
  { accessorKey: 'name', header: 'Name' },
  { accessorKey: 'scope', header: 'Scope' },
  { accessorKey: 'url', header: 'URL' },
  { accessorKey: 'enabled', header: 'Enabled' },
  { accessorKey: 'priority', header: 'Priority' },
  { id: 'actions', header: '' }
]

onMounted(async () => {
  await load()
  await loadHooks()
})

async function loadHooks() {
  loading.value = true
  try {
    hooks.value = await api.listGlobalHooks()
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to load global hooks',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    loading.value = false
  }
}

function scopeLabel(hook: HookDefinition) {
  if (hook.applyToAllCollections) return 'All collections + auth'
  return (hook.targetCollections || []).join(', ') || '—'
}

function hookToForm(hook: HookDefinition) {
  return {
    name: hook.name,
    description: hook.description || '',
    url: hook.url,
    method: hook.method || 'POST',
    timeoutMs: hook.timeoutMs ?? 5000,
    secret: hook.secret,
    enabled: hook.enabled !== false,
    priority: hook.priority ?? 100,
    includeSchema: !!hook.includeSchema,
    failOpen: !!hook.failOpen,
    applyToAllCollections: hook.applyToAllCollections === true,
    targetCollections: [...(hook.targetCollections || [])]
  }
}

function openAdd() {
  openEditor({
    mode: 'add',
    form: emptyGlobalHookForm(),
    id: null,
    save: saveHook
  })
}

function openEdit(_event: Event, tableRow: { original: HookDefinition }) {
  openEditor({
    mode: 'edit',
    form: hookToForm(tableRow.original),
    id: tableRow.original.id,
    save: saveHook
  })
}

async function saveHook() {
  if (!editorForm.value.applyToAllCollections && editorForm.value.targetCollections.length === 0) {
    toast.add({
      title: 'Select at least one collection or apply to all',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
    return
  }

  setSaving(true)
  try {
    const payload = {
      name: editorForm.value.name,
      description: editorForm.value.description,
      url: editorForm.value.url,
      method: editorForm.value.method,
      timeoutMs: editorForm.value.timeoutMs,
      secret: editorForm.value.secret,
      enabled: editorForm.value.enabled,
      priority: editorForm.value.priority,
      includeSchema: editorForm.value.includeSchema,
      failOpen: editorForm.value.failOpen,
      applyToAllCollections: editorForm.value.applyToAllCollections,
      targetCollections: editorForm.value.applyToAllCollections
        ? []
        : editorForm.value.targetCollections
    }

    if (editorId.value) {
      await api.updateGlobalHook(editorId.value, payload)
      toast.add({ title: 'Global hook saved', color: 'success', icon: 'i-lucide-circle-check' })
    } else {
      await api.createGlobalHook(payload)
      toast.add({ title: 'Global hook created', color: 'success', icon: 'i-lucide-circle-check' })
    }

    closeEditor()
    await loadHooks()
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to save global hook',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    setSaving(false)
  }
}

function requestDelete(hook: HookDefinition) {
  hookToDelete.value = hook
  deleteOpen.value = true
}

async function confirmDelete() {
  if (!hookToDelete.value) return
  try {
    await api.deleteGlobalHook(hookToDelete.value.id)
    deleteOpen.value = false
    hookToDelete.value = null
    await loadHooks()
    toast.add({ title: 'Global hook deleted', color: 'success', icon: 'i-lucide-circle-check' })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to delete global hook',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  }
}

async function runTest(hook: HookDefinition) {
  testingHookId.value = hook.id
  testResult.value = null
  testModalOpen.value = true
  try {
    testResult.value = await api.testGlobalHook(hook)
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
</script>

<template>
  <div class="space-y-4">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <p class="max-w-2xl text-sm text-muted">
        Global <code>beforeRequest</code> hooks run before every matching collection operation and
        before auth flows (login, register, refresh). They always run before collection-specific hooks.
      </p>
      <UButton icon="i-lucide-plus" :disabled="!bootstrap?.hasActiveTenant" @click="openAdd">
        Add global hook
      </UButton>
    </div>

    <UCard :ui="{ body: 'p-0 sm:p-0' }">
      <UTable :data="hooks" :columns="columns" :loading="loading" @select="openEdit">
        <template #name-cell="{ row }">
          <div>
            <span class="font-medium">{{ row.original.name }}</span>
            <p v-if="row.original.description" class="mt-0.5 text-xs text-muted">{{ row.original.description }}</p>
          </div>
        </template>
        <template #scope-cell="{ row }">
          <span class="text-sm">{{ scopeLabel(row.original) }}</span>
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
              @click="runTest(row.original)"
            >
              Test
            </UButton>
            <UButton
              size="sm"
              color="error"
              variant="soft"
              icon="i-lucide-trash-2"
              @click="requestDelete(row.original)"
            />
          </div>
        </template>
        <template #empty>
          <div class="py-10 text-center text-muted">No global hooks configured yet.</div>
        </template>
      </UTable>
    </UCard>

    <UModal v-model:open="deleteOpen" portal="body" :ui="modalUi">
      <template #content>
        <UCard>
          <template #header>
            <h3 class="font-semibold">Delete global hook</h3>
          </template>
          <p class="text-sm text-muted">
            Delete hook "{{ hookToDelete?.name }}"? This cannot be undone.
          </p>
          <template #footer>
            <div class="flex justify-end gap-2">
              <UButton variant="ghost" color="neutral" @click="deleteOpen = false">Cancel</UButton>
              <UButton color="error" icon="i-lucide-trash-2" @click="confirmDelete">Delete</UButton>
            </div>
          </template>
        </UCard>
      </template>
    </UModal>

    <UModal v-model:open="testModalOpen" portal="body" :ui="modalUi">
      <template #content>
        <UCard>
          <template #header>
            <h3 class="font-semibold">Hook test result</h3>
          </template>
          <div v-if="!testResult" class="py-8 text-center text-sm text-muted">Running test…</div>
          <div v-else class="space-y-3 text-sm">
            <p><span class="text-muted">Status:</span> {{ testResult.statusCode || '—' }}</p>
            <p><span class="text-muted">Latency:</span> {{ testResult.latencyMs }} ms</p>
            <p v-if="testResult.error" class="text-error">{{ testResult.error }}</p>
            <pre v-if="testResult.responseBody" class="overflow-x-auto rounded-lg bg-muted/40 p-3 font-mono text-xs">{{
              testResult.responseBody
            }}</pre>
          </div>
        </UCard>
      </template>
    </UModal>
  </div>
</template>
