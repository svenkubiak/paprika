<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import UserEditorSheet, {
  type UserEditorForm,
  type UserEditorMode
} from '@/components/UserEditorSheet.vue'
import { api } from '@/lib/api'
import { modalUi } from '@/lib/overlay-ui'
import { useAppToast } from '@/composables/useAppToast'
import { useBootstrap } from '@/composables/useBootstrap'
import type { TenantUser } from '@/types'

const toast = useAppToast()
const { load, bootstrap } = useBootstrap()

const users = ref<TenantUser[]>([])
const loading = ref(false)
const editorOpen = ref(false)
const editorMode = ref<UserEditorMode>('add')
const editingUser = ref<TenantUser | null>(null)
const saving = ref(false)
const deleteOpen = ref(false)
const deleting = ref(false)
const deletingUser = ref<TenantUser | null>(null)

const form = ref<UserEditorForm>({ username: '', password: '', email: '' })

const hasActiveTenant = computed(() => !!bootstrap.value?.hasActiveTenant)
const activeTenant = computed(() => bootstrap.value?.activeTenant ?? null)

const columns = [
  { id: 'userId', accessorKey: 'id', header: 'ID' },
  { accessorKey: 'username', header: 'Username' },
  { accessorKey: 'email', header: 'Email' },
  { accessorKey: 'createdAt', header: 'Created' },
  { accessorKey: 'updatedAt', header: 'Updated' },
  { id: 'actions', header: 'Actions' }
]

watch(hasActiveTenant, async (value) => {
  if (value) {
    await refresh()
  } else {
    users.value = []
  }
})

onMounted(async () => {
  await load(true)
  if (hasActiveTenant.value) {
    await refresh()
  }
})

async function refresh() {
  const tenant = activeTenant.value
  if (!tenant) {
    users.value = []
    return
  }

  loading.value = true
  try {
    users.value = await api.listTenantUsers(tenant.id)
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to load users',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.value = { username: '', password: '', email: '' }
  editingUser.value = null
}

function openCreate() {
  resetForm()
  editorMode.value = 'add'
  editorOpen.value = true
}

function userToForm(user: TenantUser): UserEditorForm {
  return {
    username: user.username,
    password: '',
    email: user.email ?? ''
  }
}

function openEdit(_event: Event, tableRow: { original: TenantUser }) {
  editingUser.value = tableRow.original
  editorMode.value = 'edit'
  form.value = userToForm(tableRow.original)
  editorOpen.value = true
}

async function saveUser() {
  const tenant = activeTenant.value
  if (!tenant) return

  const username = form.value.username.trim()
  const email = form.value.email.trim()
  const password = form.value.password

  if (!username) {
    toast.add({
      title: 'Username is required',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
    return
  }

  if (editorMode.value === 'add' && !password) {
    toast.add({
      title: 'Password is required',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
    return
  }

  saving.value = true
  try {
    if (editorMode.value === 'add') {
      await api.createTenantUser(tenant.id, username, password, email || null)
      toast.add({ title: 'User created', color: 'success', icon: 'i-lucide-circle-check' })
    } else if (editingUser.value) {
      const payload: { username: string; email: string; password?: string } = {
        username,
        email
      }
      if (password) {
        payload.password = password
      }
      await api.updateTenantUser(tenant.id, editingUser.value.id, payload)
      toast.add({ title: 'User updated', color: 'success', icon: 'i-lucide-circle-check' })
    }

    editorOpen.value = false
    await refresh()
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to save user',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    saving.value = false
  }
}

function confirmDelete(user: TenantUser) {
  deletingUser.value = user
  deleteOpen.value = true
}

async function deleteUserAction() {
  const tenant = activeTenant.value
  if (!tenant || !deletingUser.value) return

  deleting.value = true
  try {
    await api.deleteTenantUser(tenant.id, deletingUser.value.id)
    deleteOpen.value = false
    await refresh()
    toast.add({ title: 'User deleted', color: 'success', icon: 'i-lucide-circle-check' })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to delete user',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    deleting.value = false
  }
}

</script>

<template>
  <div class="space-y-4">
    <UAlert
      v-if="!hasActiveTenant"
      color="primary"
      variant="soft"
      icon="i-lucide-globe"
      title="Select a tenant"
      description="Choose a tenant in the sidebar to manage its users."
    />

    <div v-if="hasActiveTenant" class="flex justify-end">
      <UButton icon="i-lucide-user-plus" @click="openCreate">New user</UButton>
    </div>

    <UCard v-if="hasActiveTenant" :ui="{ body: 'p-0 sm:p-0' }">
      <UTable :data="users" :columns="columns" :loading="loading" @select="openEdit">
        <template #userId-cell="{ row }">
          <code class="text-sm">{{ row.original.id }}</code>
        </template>
        <template #email-cell="{ row }">
          {{ row.original.email || '—' }}
        </template>
        <template #createdAt-cell="{ row }">
          <span class="font-mono text-sm text-muted">{{ row.original.createdAt || '—' }}</span>
        </template>
        <template #updatedAt-cell="{ row }">
          <span class="font-mono text-sm text-muted">{{ row.original.updatedAt || '—' }}</span>
        </template>
        <template #actions-cell="{ row }">
          <div class="flex gap-2" @click.stop>
            <UButton
              size="sm"
              variant="soft"
              icon="i-lucide-pencil"
              @click="openEdit($event, { original: row.original })"
            >
              Edit
            </UButton>
            <UButton
              size="sm"
              color="error"
              variant="soft"
              icon="i-lucide-trash-2"
              @click="confirmDelete(row.original)"
            >
              Delete
            </UButton>
          </div>
        </template>
        <template #empty>
          <div class="py-10 text-center text-muted">
            No users yet. Create one manually, or enable self-registration under User settings.
          </div>
        </template>
      </UTable>
    </UCard>

    <UserEditorSheet
      v-model:open="editorOpen"
      :mode="editorMode"
      :form="form"
      :user="editingUser"
      :saving="saving"
      @save="saveUser"
      @update:open="(open) => { if (!open) resetForm() }"
    />

    <UModal v-model:open="deleteOpen" portal="body" :ui="modalUi">
      <template #content>
        <UCard>
          <template #header>
            <div class="flex items-center gap-2">
              <UIcon name="i-lucide-triangle-alert" class="size-5 text-error" />
              <h3 class="font-semibold">Delete user</h3>
            </div>
          </template>

          <p class="text-sm text-muted">
            Delete user "{{ deletingUser?.username }}"? This cannot be undone.
          </p>

          <template #footer>
            <div class="flex justify-end gap-2">
              <UButton variant="ghost" color="neutral" @click="deleteOpen = false">Cancel</UButton>
              <UButton
                color="error"
                :loading="deleting"
                icon="i-lucide-trash-2"
                @click="deleteUserAction"
              >
                Delete user
              </UButton>
            </div>
          </template>
        </UCard>
      </template>
    </UModal>
  </div>
</template>
