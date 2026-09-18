<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '@/lib/api'
import { modalUi, selectContentProps, selectMenuUi } from '@/lib/overlay-ui'
import { useAppToast } from '@/composables/useAppToast'
import { useBootstrap } from '@/composables/useBootstrap'
import type { ApiKey, TenantUser } from '@/types'

const toast = useAppToast()
const { load, bootstrap } = useBootstrap()

const keys = ref<ApiKey[]>([])
const users = ref<TenantUser[]>([])
const loading = ref(false)
const creating = ref(false)
const revoking = ref(false)
const deleting = ref(false)
const createOpen = ref(false)
const revokeOpen = ref(false)
const deleteOpen = ref(false)
const revokingKey = ref<ApiKey | null>(null)
const deletingKey = ref<ApiKey | null>(null)

const form = ref<{ name: string; userId: string; expiresAt: string; bypassRules: boolean }>({
  name: '',
  userId: '',
  expiresAt: '',
  bypassRules: false
})

/** Shown exactly once, right after creating: the server cannot hand it out again. */
const createdKey = ref<string | null>(null)
const copied = ref(false)

const hasActiveTenant = computed(() => !!bootstrap.value?.hasActiveTenant)
const activeTenant = computed(() => bootstrap.value?.activeTenant ?? null)

const userItems = computed(() =>
  users.value.map((user) => ({ label: user.username, value: user.id }))
)

const columns = [
  { accessorKey: 'name', header: 'Name' },
  { accessorKey: 'keyPrefix', header: 'Key' },
  { accessorKey: 'userId', header: 'Bound user' },
  { accessorKey: 'lastUsedAt', header: 'Last used' },
  { accessorKey: 'expiresAt', header: 'Expires' },
  { id: 'status', header: 'Status' },
  { id: 'actions', header: 'Actions' }
]

onMounted(async () => {
  await load(true)
  if (hasActiveTenant.value) {
    await refresh()
  }
})

watch(hasActiveTenant, async (value) => {
  if (value) {
    await refresh()
  } else {
    keys.value = []
    users.value = []
  }
})

async function refresh() {
  const tenant = activeTenant.value
  if (!tenant) {
    keys.value = []
    users.value = []
    return
  }

  loading.value = true
  try {
    const [loadedKeys, loadedUsers] = await Promise.all([
      api.listApiKeys(tenant.id),
      api.listTenantUsers(tenant.id)
    ])
    keys.value = loadedKeys
    users.value = loadedUsers
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to load API keys',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.value = { name: '', userId: '', expiresAt: '', bypassRules: false }
  createdKey.value = null
  copied.value = false
  createOpen.value = true
}

function usernameFor(userId: string): string {
  return users.value.find((user) => user.id === userId)?.username ?? userId
}

function statusOf(key: ApiKey): { label: string; color: 'success' | 'neutral' | 'error' } {
  if (key.revokedAt) return { label: 'revoked', color: 'error' }
  if (key.expiresAt && new Date(key.expiresAt).getTime() <= Date.now()) {
    return { label: 'expired', color: 'error' }
  }
  return { label: 'active', color: 'success' }
}

async function createKey() {
  const tenant = activeTenant.value
  if (!tenant) return

  const name = form.value.name.trim()
  if (!name || !form.value.userId) {
    toast.add({
      title: 'Name and bound user are required',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
    return
  }

  creating.value = true
  try {
    const created = await api.createApiKey(tenant.id, {
      name,
      userId: form.value.userId,
      expiresAt: form.value.expiresAt ? new Date(form.value.expiresAt).toISOString() : null,
      bypassRules: form.value.bypassRules
    })
    createdKey.value = created.key
    await refresh()
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to create API key',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    creating.value = false
  }
}

async function copyKey() {
  if (!createdKey.value) return
  try {
    await navigator.clipboard.writeText(createdKey.value)
    copied.value = true
  } catch {
    toast.add({
      title: 'Copying failed, select the key and copy it manually',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  }
}

function confirmRevoke(key: ApiKey) {
  revokingKey.value = key
  revokeOpen.value = true
}

function confirmDelete(key: ApiKey) {
  deletingKey.value = key
  deleteOpen.value = true
}

async function revokeKey() {
  const tenant = activeTenant.value
  if (!tenant || !revokingKey.value) return

  revoking.value = true
  try {
    await api.revokeApiKey(tenant.id, revokingKey.value.id)
    revokeOpen.value = false
    await refresh()
    toast.add({ title: 'API key revoked', color: 'success', icon: 'i-lucide-circle-check' })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to revoke API key',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    revoking.value = false
  }
}

async function deleteKey() {
  const tenant = activeTenant.value
  if (!tenant || !deletingKey.value) return

  deleting.value = true
  try {
    await api.deleteApiKey(tenant.id, deletingKey.value.id)
    deleteOpen.value = false
    await refresh()
    toast.add({ title: 'API key deleted', color: 'success', icon: 'i-lucide-circle-check' })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to delete API key',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <UCard v-if="hasActiveTenant" :ui="{ body: 'p-4 sm:p-4' }">
    <div class="flex flex-wrap items-start justify-between gap-3">
      <div>
        <p class="font-medium">API keys</p>
        <p class="mt-1 max-w-3xl text-sm text-muted">
          Long-lived credentials for backends that cannot log in with a password. A key is sent as
          <code class="text-xs">Authorization: Bearer pk_…</code> and authenticates as the user it
          is bound to — it can do everything that user's
          <a class="underline" href="/admin/collections/users/rules">collection rules</a> allow,
          including issuing tokens for other users if that user is a token issuer. Keys never grant
          superadmin rights and never bypass rules.
        </p>
      </div>
      <UButton icon="i-lucide-key-round" @click="openCreate">New API key</UButton>
    </div>

    <div class="mt-4 -mx-4 sm:-mx-4">
      <UTable :data="keys" :columns="columns" :loading="loading">
        <template #name-cell="{ row }">
          <div class="flex items-center gap-2">
            <span>{{ row.original.name }}</span>
            <UBadge
              v-if="row.original.bypassRules"
              color="error"
              variant="soft"
              size="xs"
              title="Requests with this key skip the collection rules of this tenant"
            >
              bypasses rules
            </UBadge>
          </div>
        </template>
        <template #keyPrefix-cell="{ row }">
          <code class="text-sm">{{ row.original.keyPrefix }}…</code>
        </template>
        <template #userId-cell="{ row }">
          <span class="text-sm">{{ usernameFor(row.original.userId) }}</span>
        </template>
        <template #lastUsedAt-cell="{ row }">
          <span class="font-mono text-sm text-muted">{{ row.original.lastUsedAt || 'never' }}</span>
        </template>
        <template #expiresAt-cell="{ row }">
          <span class="font-mono text-sm text-muted">{{ row.original.expiresAt || '—' }}</span>
        </template>
        <template #status-cell="{ row }">
          <UBadge :color="statusOf(row.original).color" variant="soft">
            {{ statusOf(row.original).label }}
          </UBadge>
        </template>
        <template #actions-cell="{ row }">
          <div class="flex justify-end gap-2" @click.stop>
            <UButton
              v-if="!row.original.revokedAt"
              size="sm"
              color="error"
              variant="soft"
              icon="i-lucide-ban"
              @click="confirmRevoke(row.original)"
            >
              Revoke
            </UButton>
            <UButton
              size="sm"
              color="neutral"
              variant="soft"
              icon="i-lucide-trash-2"
              :aria-label="'Delete ' + row.original.name"
              title="Remove this key and its record entirely"
              @click="confirmDelete(row.original)"
            />
          </div>
        </template>
        <template #empty>
          <div class="py-8 text-center text-sm text-muted">
            No API keys for this tenant yet.
          </div>
        </template>
      </UTable>
    </div>

    <UModal v-model:open="createOpen" portal="body" :ui="modalUi">
      <template #content>
        <UCard>
          <template #header>
            <div class="flex items-center gap-2">
              <UIcon name="i-lucide-key-round" class="size-5 text-primary" />
              <h3 class="font-semibold">{{ createdKey ? 'API key created' : 'New API key' }}</h3>
            </div>
          </template>

          <div v-if="createdKey" class="space-y-3">
            <UAlert
              color="warning"
              variant="soft"
              icon="i-lucide-triangle-alert"
              title="Copy this key now"
              description="Paprika stores only a hash of it. This is the only time it can be shown; if it is lost, revoke the key and create a new one."
            />
            <div class="flex items-center gap-2">
              <UInput :model-value="createdKey" readonly class="w-full font-mono" />
              <UButton
                :icon="copied ? 'i-lucide-check' : 'i-lucide-copy'"
                color="neutral"
                variant="soft"
                aria-label="Copy API key"
                @click="copyKey"
              />
            </div>
            <p class="text-sm text-muted">
              Anyone holding this key <strong>is</strong> the bound user
              ({{ usernameFor(form.userId) }}), with every permission that user's rules grant.
              Store it server-side only.
              <template v-if="form.bypassRules">
                This key <strong>bypasses the collection rules</strong>: it can read and write all
                data of this tenant.
              </template>
            </p>
          </div>

          <form v-else class="space-y-4" @submit.prevent="createKey">
            <UFormField
              label="Name"
              help="Who or what uses this key, e.g. middleware-prod. Shown in the list and the request log."
              required
              class="w-full"
            >
              <UInput v-model="form.name" class="w-full" placeholder="middleware-prod" />
            </UFormField>

            <UFormField
              label="Bound user"
              help="The key authenticates as this tenant user and inherits exactly that user's permissions."
              required
              class="w-full"
            >
              <USelect
                v-model="form.userId"
                :items="userItems"
                placeholder="Select a user"
                :content="selectContentProps"
                :ui="selectMenuUi"
                class="w-full"
              />
            </UFormField>

            <div class="space-y-3 rounded-lg border border-default p-3">
              <div class="flex items-start justify-between gap-3">
                <div>
                  <p class="text-sm font-medium">Bypass collection rules</p>
                  <p class="mt-1 text-sm text-muted">
                    For a trusted backend service. Requests with this key are not checked against
                    the collection rules at all.
                  </p>
                </div>
                <USwitch v-model="form.bypassRules" />
              </div>
              <UAlert
                v-if="form.bypassRules"
                color="error"
                variant="soft"
                icon="i-lucide-shield-alert"
                title="This key reads and writes all data of this tenant"
                description="Every record of every collection, across all users, even where the rules say No access. It still cannot reach the admin API, another tenant, or superadmin functions, and hooks keep running. The flag cannot be changed later — revoke the key and create a new one instead."
              />
            </div>

            <UFormField
              label="Expires"
              help="Optional. Leave empty for a key that never expires on its own; it can always be revoked."
              class="w-full"
            >
              <UInput v-model="form.expiresAt" type="date" class="w-full font-mono" />
            </UFormField>
          </form>

          <template #footer>
            <div class="flex justify-end gap-2">
              <UButton
                v-if="createdKey"
                icon="i-lucide-check"
                @click="createOpen = false"
              >
                Done
              </UButton>
              <template v-else>
                <UButton variant="ghost" color="neutral" @click="createOpen = false">Cancel</UButton>
                <UButton :loading="creating" icon="i-lucide-key-round" @click="createKey">
                  Create key
                </UButton>
              </template>
            </div>
          </template>
        </UCard>
      </template>
    </UModal>

    <UModal v-model:open="revokeOpen" portal="body" :ui="modalUi">
      <template #content>
        <UCard>
          <template #header>
            <div class="flex items-center gap-2">
              <UIcon name="i-lucide-triangle-alert" class="size-5 text-error" />
              <h3 class="font-semibold">Revoke API key</h3>
            </div>
          </template>

          <p class="text-sm text-muted">
            Revoke "{{ revokingKey?.name }}"? Every request using it fails immediately afterwards.
            This cannot be undone — issue a new key instead.
          </p>

          <template #footer>
            <div class="flex justify-end gap-2">
              <UButton variant="ghost" color="neutral" @click="revokeOpen = false">Cancel</UButton>
              <UButton color="error" :loading="revoking" icon="i-lucide-ban" @click="revokeKey">
                Revoke key
              </UButton>
            </div>
          </template>
        </UCard>
      </template>
    </UModal>
    <UModal v-model:open="deleteOpen" portal="body" :ui="modalUi">
      <template #content>
        <UCard>
          <template #header>
            <div class="flex items-center gap-2">
              <UIcon name="i-lucide-triangle-alert" class="size-5 text-error" />
              <h3 class="font-semibold">Delete API key</h3>
            </div>
          </template>

          <div class="space-y-3">
            <p class="text-sm text-muted">
              Delete "{{ deletingKey?.name }}" including its record? It disappears from this list,
              so you lose the history of when it was last used.
            </p>
            <UAlert
              v-if="deletingKey && !deletingKey.revokedAt"
              color="warning"
              variant="soft"
              icon="i-lucide-triangle-alert"
              title="This key is still active"
              description="Deleting it stops every request using it immediately, without leaving a trace that it existed. If you only want to retire it, revoke it instead."
            />
          </div>

          <template #footer>
            <div class="flex justify-end gap-2">
              <UButton variant="ghost" color="neutral" @click="deleteOpen = false">Cancel</UButton>
              <UButton color="error" :loading="deleting" icon="i-lucide-trash-2" @click="deleteKey">
                Delete key
              </UButton>
            </div>
          </template>
        </UCard>
      </template>
    </UModal>
  </UCard>
</template>
