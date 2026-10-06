<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '@/lib/api'
import PField from '@/components/PField.vue'
import PSwitchField from '@/components/PSwitchField.vue'
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
const sourceOpen = ref(false)
const savingSource = ref(false)
const sourceKey = ref<ApiKey | null>(null)
const sourceInput = ref('')

const form = ref<{
  name: string
  userId: string
  expiresAt: string
  bypassRules: boolean
  bypassHooks: boolean
  allowedCidrs: string
}>({
  name: '',
  userId: '',
  expiresAt: '',
  bypassRules: false,
  bypassHooks: false,
  allowedCidrs: ''
})

/** The server normalises and rejects unparsable ranges, so nothing is validated here. */
function parseCidrs(value: string): string[] {
  return value
    .split(/[\n,;]+/)
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0)
}

/** Shown once right after creating: the server cannot hand it out again. */
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
  { id: 'allowedCidrs', header: 'Source' },
  { accessorKey: 'lastUsedAt', header: 'Last used' },
  { accessorKey: 'expiresAt', header: 'Expires' },
  { id: 'status', header: 'Status' },
  { id: 'actions', header: '' }
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
  form.value = {
    name: '',
    userId: '',
    expiresAt: '',
    bypassRules: false,
    bypassHooks: false,
    allowedCidrs: ''
  }
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
      bypassRules: form.value.bypassRules,
      bypassHooks: form.value.bypassHooks,
      allowedCidrs: parseCidrs(form.value.allowedCidrs)
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

function editSource(key: ApiKey) {
  sourceKey.value = key
  sourceInput.value = (key.allowedCidrs ?? []).join('\n')
  sourceOpen.value = true
}

async function saveSource() {
  const tenant = activeTenant.value
  if (!tenant || !sourceKey.value) return

  savingSource.value = true
  try {
    await api.updateApiKeyAllowedCidrs(tenant.id, sourceKey.value.id, parseCidrs(sourceInput.value))
    sourceOpen.value = false
    await refresh()
    toast.add({ title: 'Source binding updated', color: 'success', icon: 'i-lucide-circle-check' })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to update the source binding',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    savingSource.value = false
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
      <UTable
        :data="keys"
        :columns="columns"
        :loading="loading"
        :column-pinning="{ right: ['actions'] }"
      >
        <template #name-cell="{ row }">
          <div class="flex items-center gap-2">
            <span>{{ row.original.name }}</span>
            <UBadge
              v-if="row.original.bypassRules"
              color="error"
              variant="soft"
              title="Requests with this key skip the collection rules of this tenant"
            >
              bypasses rules
            </UBadge>
            <UBadge
              v-if="row.original.bypassHooks"
              color="warning"
              variant="soft"
              title="Requests with this key run no hooks of this tenant"
            >
              bypasses hooks
            </UBadge>
          </div>
        </template>
        <template #keyPrefix-cell="{ row }">
          <code class="text-sm">{{ row.original.keyPrefix }}…</code>
        </template>
        <template #userId-cell="{ row }">
          <span class="text-sm">{{ usernameFor(row.original.userId) }}</span>
        </template>
        <template #allowedCidrs-cell="{ row }">
          <UBadge
            v-if="row.original.allowedCidrs?.length"
            color="success"
            variant="soft"
            :title="row.original.allowedCidrs.join('\n')"
          >
            {{ row.original.allowedCidrs.length }}
            {{ row.original.allowedCidrs.length === 1 ? 'range' : 'ranges' }}
          </UBadge>
          <span v-else class="text-sm text-muted" title="This key works from any address">
            anywhere
          </span>
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
          <RowActions @delete="confirmDelete(row.original)">
            <UButton
              size="sm"
              color="neutral"
              variant="soft"
              icon="i-lucide-network"
              title="Restrict this key to source address ranges"
              @click="editSource(row.original)"
            >
              Source
            </UButton>
            <UButton
              v-if="!row.original.revokedAt"
              size="sm"
              color="warning"
              variant="soft"
              icon="i-lucide-ban"
              @click="confirmRevoke(row.original)"
            >
              Revoke
            </UButton>
          </RowActions>
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
              <template v-if="form.bypassHooks">
                This key <strong>runs no hooks</strong>: nothing a hook checks, logs or rewrites
                applies to its requests.
              </template>
              <template v-if="parseCidrs(form.allowedCidrs).length">
                It is only accepted from
                <strong>{{ parseCidrs(form.allowedCidrs).join(', ') }}</strong>.
              </template>
            </p>
          </div>

          <form v-else class="space-y-4" @submit.prevent="createKey">
            <PField
              label="Name"
              icon="i-lucide-tag"
              help="Who or what uses this key, e.g. middleware-prod."
              details="Shown in the list and in the request log."
            >
              <UInput v-model="form.name" placeholder="middleware-prod" />
            </PField>

            <PField
              label="Bound user"
              icon="i-lucide-user"
              help="The key authenticates as this tenant user and inherits exactly that user's permissions."
            >
              <USelect
                v-model="form.userId"
                :items="userItems"
                placeholder="Select a user"
                :content="selectContentProps"
                :ui="selectMenuUi"
              />
            </PField>

            <div class="space-y-3">
              <PSwitchField
                v-model="form.bypassRules"
                label="Bypass collection rules"
                icon="i-lucide-shield-off"
                help="For a trusted backend service."
                details="Requests with this key are not checked against the collection rules at all."
              />
              <UAlert
                v-if="form.bypassRules"
                color="error"
                variant="soft"
                icon="i-lucide-shield-alert"
                title="This key reads and writes all data of this tenant"
                description="Every record of every collection, across all users, even where the rules say No access. It still cannot reach the admin API, another tenant, or superadmin functions, and hooks keep running unless you switch them off below as well. The flag cannot be changed later — revoke the key and create a new one instead."
              />
            </div>

            <div class="space-y-3">
              <PSwitchField
                v-model="form.bypassHooks"
                label="Bypass hooks"
                icon="i-lucide-webhook-off"
                help="For the service a hook itself calls back into Paprika."
                details="No hook runs for requests with this key — neither the global beforeRequest hooks nor the collection hooks."
              />
              <UAlert
                v-if="form.bypassHooks"
                color="warning"
                variant="soft"
                icon="i-lucide-webhook-off"
                title="Hooks stop applying to this key"
                description="A hook used as an external authorizer no longer sees these requests, and neither do hooks that log or narrow machine writes. Use this only where a hook target asks Paprika back with this key and would otherwise re-enter the very hook it came from. Requests made with it carry a hooks bypassed badge in the request log. The flag cannot be changed later — revoke the key and create a new one instead."
              />
            </div>

            <PField
              label="Allowed source ranges"
              icon="i-lucide-network"
              optional
              help="CIDR ranges this key may be presented from, one per line. Empty means anywhere."
              details="IPv4 and IPv6, e.g. 10.200.0.0/24 or 2a01:4f8:c17:c74c::1/128. A bare address becomes a /32 or /128. Checked against the peer of the TCP connection, never against X-Forwarded-For — so it binds a caller that reaches Paprika directly, not one that comes through your reverse proxy."
            >
              <UTextarea
                v-model="form.allowedCidrs"
                :rows="3"
                class="w-full font-mono"
                placeholder="10.200.0.0/24"
              />
            </PField>

            <PField
              label="Expires"
              icon="i-lucide-calendar"
              optional
              width="md"
              help="Leave empty for a key that never expires on its own; it can always be revoked."
            >
              <UInput v-model="form.expiresAt" type="date" class="font-mono" />
            </PField>
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

    <UModal v-model:open="sourceOpen" portal="body" :ui="modalUi">
      <template #content>
        <UCard>
          <template #header>
            <div class="flex items-center gap-2">
              <UIcon name="i-lucide-network" class="size-5 text-primary" />
              <h3 class="font-semibold">Source ranges</h3>
            </div>
          </template>

          <div class="space-y-3">
            <p class="text-sm text-muted">
              Which addresses "{{ sourceKey?.name }}" may be presented from, one CIDR range per
              line. Leave this empty to let the key work from anywhere.
            </p>
            <UTextarea
              v-model="sourceInput"
              :rows="4"
              class="w-full font-mono"
              placeholder="10.200.0.0/24"
            />
            <UAlert
              color="warning"
              variant="soft"
              icon="i-lucide-info"
              title="The address checked is the peer of the connection"
              description="Not X-Forwarded-For — a header the caller writes must not be able to lift this. If the key reaches Paprika through your reverse proxy, the proxy is what you would be binding here; a server-to-server integration on the internal network is the case this is for. Unlike the bypass flags, this can be changed at any time: it only ever narrows where the key works."
            />
          </div>

          <template #footer>
            <div class="flex justify-end gap-2">
              <UButton variant="ghost" color="neutral" @click="sourceOpen = false">Cancel</UButton>
              <UButton :loading="savingSource" icon="i-lucide-check" @click="saveSource">
                Save
              </UButton>
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
