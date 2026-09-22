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
import { formatCellValue } from '@/lib/utils'
import { validateRecordValues } from '@/lib/field-validation'
import { buildRecordFormState, serializeRecordForm } from '@/lib/record-form'
import type { FieldDefinition, TenantUser } from '@/types'

/** Owned by the server or edited through their own inputs above the custom fields. */
const CORE_USER_FIELDS = ['username', 'email', 'password', 'role']

const toast = useAppToast()
const { load, bootstrap } = useBootstrap()

const users = ref<TenantUser[]>([])
/** The fields this tenant added to its users schema - empty for a tenant that uses only the core. */
const customFields = ref<FieldDefinition[]>([])
const loading = ref(false)
const editorOpen = ref(false)
const editorMode = ref<UserEditorMode>('add')
const editingUser = ref<TenantUser | null>(null)
const saving = ref(false)
const deleteOpen = ref(false)
const deleting = ref(false)
const deletingUser = ref<TenantUser | null>(null)
const bulkDeleteOpen = ref(false)
const search = ref('')
// A custom field of the users schema is a valid sort key too, so this cannot be narrowed to the
// core fields of TenantUser.
const sortField = ref<string>('updatedAt')
const sortDirection = ref<'asc' | 'desc'>('desc')
const page = ref(1)
const pageSize = ref(25)
const selectedIds = ref<Set<string>>(new Set())

function emptyCustomState() {
  return { values: {}, jsonText: {}, dateTimeInitial: {} }
}

const form = ref<UserEditorForm>({
  username: '',
  password: '',
  email: '',
  custom: emptyCustomState()
})

const hasActiveTenant = computed(() => !!bootstrap.value?.hasActiveTenant)
const activeTenant = computed(() => bootstrap.value?.activeTenant ?? null)

// The custom fields sit between the core fields and the timestamps, the same order the record
// table of a regular collection uses.
const columns = computed(() => [
  { id: 'select', header: '' },
  { id: 'userId', accessorKey: 'id', header: 'ID' },
  { accessorKey: 'username', header: 'Username' },
  { accessorKey: 'email', header: 'Email' },
  ...customFields.value.map((field) => ({ accessorKey: field.name, header: field.name })),
  { accessorKey: 'createdAt', header: 'Created' },
  { accessorKey: 'updatedAt', header: 'Updated' },
  { id: 'actions', header: 'Actions' }
])

const pageSizeOptions = [
  { label: '10', value: 10 },
  { label: '25', value: 25 },
  { label: '50', value: 50 },
  { label: '100', value: 100 }
]

const sortOptions = computed(() => [
  { label: 'Updated', value: 'updatedAt' },
  { label: 'Created', value: 'createdAt' },
  { label: 'ID', value: 'id' },
  { label: 'Username', value: 'username' },
  { label: 'Email', value: 'email' },
  ...customFields.value.map((field) => ({ label: field.name, value: field.name }))
])

function cellText(user: TenantUser, field: FieldDefinition): string {
  return formatCellValue(user[field.name], field.type)
}

// The users endpoint hands out the full list in one response, so searching, sorting and paging all
// happen here instead of going back to the server for every keystroke or page change.
const filteredUsers = computed(() => {
  const query = search.value.trim().toLowerCase()
  // Searching over the serialized record covers the custom fields without having to know their
  // types here.
  const items = query
    ? users.value.filter((user) => JSON.stringify(user).toLowerCase().includes(query))
    : [...users.value]

  items.sort((a, b) => {
    const left = a[sortField.value]
    const right = b[sortField.value]
    const compare = String(left ?? '').localeCompare(String(right ?? ''), undefined, {
      numeric: true
    })
    return sortDirection.value === 'asc' ? compare : -compare
  })

  return items
})

const pageCount = computed(() => Math.max(1, Math.ceil(filteredUsers.value.length / pageSize.value)))

const pagedUsers = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return filteredUsers.value.slice(start, start + pageSize.value)
})

const summary = computed(() => {
  if (loading.value) return 'Loading users…'
  const count = filteredUsers.value.length
  if (count === 0) return '0 users'
  const from = (page.value - 1) * pageSize.value + 1
  const to = Math.min(page.value * pageSize.value, count)
  return `${from}–${to} of ${count} users`
})

// A shrinking result set (search, page size, deletions) can leave the current page beyond the end,
// which would render an empty table with rows still to be shown.
watch([pageCount, pageSize], () => {
  if (page.value > pageCount.value) {
    page.value = pageCount.value
  }
})

watch(search, () => {
  page.value = 1
})

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

/**
 * The custom fields come from the users schema of the active tenant. A failure here must not take
 * the user list down with it: without the schema the editor simply falls back to the core fields.
 */
async function loadCustomFields() {
  try {
    const definition = await api.getCollectionDefinition('users')
    customFields.value = (definition.fields || []).filter(
      (field) => !CORE_USER_FIELDS.includes(field.name)
    )
  } catch {
    customFields.value = []
  }
}

async function refresh() {
  const tenant = activeTenant.value
  if (!tenant) {
    users.value = []
    customFields.value = []
    return
  }

  loading.value = true
  try {
    await loadCustomFields()
    users.value = await api.listTenantUsers(tenant.id)
    selectedIds.value = new Set()
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

function toggleAll(checked: boolean) {
  const next = new Set(selectedIds.value)
  for (const user of pagedUsers.value) {
    if (checked) next.add(user.id)
    else next.delete(user.id)
  }
  selectedIds.value = next
}

function toggleOne(id: string, checked: boolean) {
  const next = new Set(selectedIds.value)
  if (checked) next.add(id)
  else next.delete(id)
  selectedIds.value = next
}

function resetForm() {
  form.value = {
    username: '',
    password: '',
    email: '',
    custom: buildRecordFormState({}, customFields.value, 'new')
  }
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
    email: user.email ?? '',
    custom: buildRecordFormState(user, customFields.value, 'edit')
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

  // The custom fields go through the same conversion and the same client-side checks as a record
  // of any other collection, so the editor cannot send something /api/collections/users would
  // refuse afterwards.
  let custom: Record<string, unknown>
  try {
    custom = serializeRecordForm(
      editableCustomFields(),
      form.value.custom,
      editorMode.value === 'add' ? 'new' : 'edit'
    )
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Invalid field value',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
    return
  }

  const issues = validateRecordValues(editableCustomFields(), custom)
  if (issues.length > 0) {
    toast.add({ title: issues[0].message, color: 'error', icon: 'i-lucide-circle-x' })
    return
  }

  saving.value = true
  try {
    if (editorMode.value === 'add') {
      await api.createTenantUser(tenant.id, username, password, email || null, custom)
      toast.add({ title: 'User created', color: 'success', icon: 'i-lucide-circle-check' })
    } else if (editingUser.value) {
      const payload: { username: string; email: string; password?: string } & Record<string, unknown> = {
        ...custom,
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

/** FILE fields cannot be edited in this sheet, so they are not part of what it sends either. */
function editableCustomFields(): FieldDefinition[] {
  return customFields.value.filter((field) => field.type !== 'FILE')
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

async function bulkDelete() {
  const tenant = activeTenant.value
  if (!tenant) return

  const ids = Array.from(selectedIds.value)
  deleting.value = true
  try {
    await Promise.all(ids.map((id) => api.deleteTenantUser(tenant.id, id)))
    bulkDeleteOpen.value = false
    toast.add({
      title: ids.length === 1 ? 'User deleted' : `${ids.length} users deleted`,
      color: 'success',
      icon: 'i-lucide-circle-check'
    })
    await refresh()
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to delete users',
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

    <div v-if="hasActiveTenant" class="flex flex-wrap items-center justify-between gap-3">
      <p class="text-sm text-muted">{{ summary }}</p>
      <UButton icon="i-lucide-user-plus" @click="openCreate">New user</UButton>
    </div>

    <UCard v-if="hasActiveTenant">
      <div class="mb-4 flex flex-wrap items-center gap-3">
        <UInput
          v-model="search"
          icon="i-lucide-search"
          placeholder="Search users…"
          class="w-full min-w-0 sm:w-auto sm:min-w-56 sm:flex-1"
        />

        <USelect
          v-model="sortField"
          :items="sortOptions"
          icon="i-lucide-arrow-up-down"
          placeholder="Sort by"
          class="w-full min-w-0 sm:w-auto sm:min-w-40"
        />

        <USelect
          v-model="sortDirection"
          :items="[
            { label: 'Ascending', value: 'asc' },
            { label: 'Descending', value: 'desc' }
          ]"
          icon="i-lucide-list-ordered"
          class="w-full min-w-0 sm:w-auto sm:min-w-36"
        />

        <UButton
          v-if="selectedIds.size > 0"
          color="error"
          variant="soft"
          icon="i-lucide-trash-2"
          @click="bulkDeleteOpen = true"
        >
          Delete selected ({{ selectedIds.size }})
        </UButton>
      </div>

      <UTable :data="pagedUsers" :columns="columns" :loading="loading" @select="openEdit">
        <template #select-header>
          <UCheckbox
            :model-value="
              pagedUsers.length > 0 && pagedUsers.every((user) => selectedIds.has(user.id))
            "
            @update:model-value="toggleAll(!!$event)"
          />
        </template>
        <template #select-cell="{ row }">
          <UCheckbox
            :model-value="selectedIds.has(row.original.id)"
            @update:model-value="toggleOne(row.original.id, !!$event)"
            @click.stop
          />
        </template>
        <template #userId-cell="{ row }">
          <code class="text-sm">{{ row.original.id }}</code>
        </template>
        <template #email-cell="{ row }">
          {{ row.original.email || '—' }}
        </template>
        <template
          v-for="field in customFields"
          :key="field.name"
          #[`${field.name}-cell`]="{ row }"
        >
          <span class="text-sm">{{ cellText(row.original, field) }}</span>
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
            {{
              search.trim()
                ? 'No users match your search.'
                : 'No users yet. Create one manually, or enable self-registration under User settings.'
            }}
          </div>
        </template>
      </UTable>

      <div
        class="mt-4 flex flex-wrap items-center justify-between gap-3 border-t border-default pt-4"
      >
        <div class="flex items-center gap-2 text-sm text-muted">
          <span>Rows per page</span>
          <USelect v-model="pageSize" :items="pageSizeOptions" class="w-24" />
        </div>
        <div class="flex items-center gap-2">
          <UButton
            icon="i-lucide-chevron-left"
            variant="ghost"
            color="neutral"
            :disabled="page <= 1"
            @click="page--"
          />
          <span class="min-w-16 text-center text-sm">{{ page }} / {{ pageCount }}</span>
          <UButton
            icon="i-lucide-chevron-right"
            variant="ghost"
            color="neutral"
            :disabled="page >= pageCount"
            @click="page++"
          />
        </div>
      </div>
    </UCard>

    <UserEditorSheet
      v-model:open="editorOpen"
      :mode="editorMode"
      :form="form"
      :custom-fields="customFields"
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

    <UModal v-model:open="bulkDeleteOpen" portal="body" :ui="modalUi">
      <template #content>
        <UCard>
          <template #header>
            <div class="flex items-center gap-2">
              <UIcon name="i-lucide-triangle-alert" class="size-5 text-error" />
              <h3 class="font-semibold">Delete users</h3>
            </div>
          </template>

          <p class="text-sm text-muted">
            Delete {{ selectedIds.size }} selected user{{ selectedIds.size === 1 ? '' : 's' }}? This
            cannot be undone.
          </p>

          <template #footer>
            <div class="flex justify-end gap-2">
              <UButton variant="ghost" color="neutral" @click="bulkDeleteOpen = false">
                Cancel
              </UButton>
              <UButton color="error" :loading="deleting" icon="i-lucide-trash-2" @click="bulkDelete">
                Delete
              </UButton>
            </div>
          </template>
        </UCard>
      </template>
    </UModal>
  </div>
</template>
