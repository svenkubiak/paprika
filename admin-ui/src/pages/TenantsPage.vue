<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import TenantEditorSheet, {
  type TenantEditorForm,
  type TenantEditorMode
} from '@/components/TenantEditorSheet.vue'
import { api } from '@/lib/api'
import { normalizeSlug } from '@/lib/utils'
import { modalUi } from '@/lib/overlay-ui'
import { useBootstrap } from '@/composables/useBootstrap'
import { useAppToast } from '@/composables/useAppToast'
import type { TenantDefinition } from '@/types'

const router = useRouter()
const toast = useAppToast()
const { load } = useBootstrap()

const tenants = ref<TenantDefinition[]>([])
const loading = ref(true)
const editorOpen = ref(false)
const editorMode = ref<TenantEditorMode>('add')
const editingTenant = ref<TenantDefinition | null>(null)
const saving = ref(false)
const deleteOpen = ref(false)
const deletingTenant = ref<TenantDefinition | null>(null)
const deleting = ref(false)
const defaultTenantId = ref<string | null>(null)

const isDeletingDefaultTenant = computed(
  () => !!deletingTenant.value && deletingTenant.value.id === defaultTenantId.value
)

const form = ref<TenantEditorForm>({
  name: '',
  slug: '',
  registrationEnabled: false,
  passwordResetEnabled: false,
  emailVerificationEnabled: false,
  emailVerificationRequired: false,
  passwordResetUrl: '',
  emailVerificationUrl: ''
})

const columns = [
  { accessorKey: 'name', header: 'Name' },
  { accessorKey: 'slug', header: 'Slug' },
  { accessorKey: 'databaseName', header: 'Database' },
  { accessorKey: 'status', header: 'Status' },
  { id: 'actions', header: 'Actions' }
]

const rows = computed(() => tenants.value)

onMounted(async () => {
  await refresh()
  await loadDefaultTenantId()
})

async function refresh() {
  loading.value = true
  try {
    tenants.value = await api.listTenants()
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to load tenants',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    loading.value = false
  }
}

async function loadDefaultTenantId() {
  try {
    const settings = await api.getSettings()
    defaultTenantId.value = settings.defaultTenantId || null
  } catch {
    defaultTenantId.value = null
  }
}

function resetForm() {
  form.value = {
    name: '',
    slug: '',
    registrationEnabled: false,
    passwordResetEnabled: false,
    emailVerificationEnabled: false,
    passwordResetUrl: '',
    emailVerificationUrl: ''
  }
  editingTenant.value = null
}

function openCreate() {
  resetForm()
  editorMode.value = 'add'
  editorOpen.value = true
}

function tenantToForm(tenant: TenantDefinition): TenantEditorForm {
  return {
    name: tenant.name,
    slug: tenant.slug,
    registrationEnabled: tenant.registrationEnabled ?? false,
    passwordResetEnabled: tenant.passwordResetEnabled ?? false,
    emailVerificationEnabled: tenant.emailVerificationEnabled ?? false,
    emailVerificationRequired: tenant.emailVerificationRequired ?? false,
    passwordResetUrl: tenant.passwordResetUrl ?? '',
    emailVerificationUrl: tenant.emailVerificationUrl ?? ''
  }
}

function openEdit(_event: Event, tableRow: { original: TenantDefinition }) {
  editingTenant.value = tableRow.original
  editorMode.value = 'edit'
  form.value = tenantToForm(tableRow.original)
  editorOpen.value = true
}

async function saveTenant() {
  const name = form.value.name.trim()
  const slug = normalizeSlug(form.value.slug)

  if (!name || !slug) {
    toast.add({
      title: 'Name and slug are required',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
    return
  }

  saving.value = true
  try {
    if (editorMode.value === 'add') {
      await api.createTenant(name, slug)
      toast.add({ title: 'Tenant created', color: 'success', icon: 'i-lucide-circle-check' })
    } else if (editingTenant.value) {
      await api.updateTenant(editingTenant.value.id, {
        name,
        slug,
        registrationEnabled: form.value.registrationEnabled,
        passwordResetEnabled: form.value.passwordResetEnabled,
        emailVerificationEnabled: form.value.emailVerificationEnabled,
        emailVerificationRequired: form.value.emailVerificationRequired,
        passwordResetUrl: form.value.passwordResetUrl.trim() || null,
        emailVerificationUrl: form.value.emailVerificationUrl.trim() || null
      })
      toast.add({ title: 'Tenant updated', color: 'success', icon: 'i-lucide-circle-check' })
    }

    editorOpen.value = false
    await refresh()
    await load(true)
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to save tenant',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    saving.value = false
  }
}

async function selectTenant(id: string) {
  try {
    await api.switchTenant(id)
    await load(true)
    await router.push('/')
    toast.add({ title: 'Tenant selected', color: 'success', icon: 'i-lucide-circle-check' })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to select tenant',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  }
}

function confirmDelete(tenant: TenantDefinition) {
  deletingTenant.value = tenant
  deleteOpen.value = true
}

async function deleteTenantAction() {
  if (!deletingTenant.value) return
  deleting.value = true
  try {
    await api.deleteTenant(deletingTenant.value.id)
    deleteOpen.value = false
    if (isDeletingDefaultTenant.value) {
      defaultTenantId.value = null
    }
    await refresh()
    await load(true)
    toast.add({ title: 'Tenant deleted', color: 'success', icon: 'i-lucide-circle-check' })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to delete tenant',
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
    <div class="flex justify-end">
      <UButton icon="i-lucide-plus" @click="openCreate">New tenant</UButton>
    </div>

    <UCard :ui="{ body: 'p-0 sm:p-0' }">
      <UTable :data="rows" :columns="columns" :loading="loading" @select="openEdit">
        <template #slug-cell="{ row }">
          <code class="text-sm">{{ row.original.slug }}</code>
        </template>
        <template #databaseName-cell="{ row }">
          <code class="text-sm">{{ row.original.databaseName }}</code>
        </template>
        <template #status-cell="{ row }">
          <UBadge
            :color="row.original.status === 'active' ? 'success' : 'neutral'"
            variant="soft"
          >
            {{ row.original.status }}
          </UBadge>
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
              variant="soft"
              icon="i-lucide-check"
              @click="selectTenant(row.original.id)"
            >
              Select
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
            No tenants yet. Create your first tenant to get started.
          </div>
        </template>
      </UTable>
    </UCard>

    <TenantEditorSheet
      v-model:open="editorOpen"
      :mode="editorMode"
      :form="form"
      :tenant="editingTenant"
      :saving="saving"
      @save="saveTenant"
      @update:open="(open) => { if (!open) resetForm() }"
    />

    <UModal v-model:open="deleteOpen" portal="body" :ui="modalUi">
      <template #content>
        <UCard>
          <template #header>
            <div class="flex items-center gap-2">
              <UIcon name="i-lucide-triangle-alert" class="size-5 text-error" />
              <h3 class="font-semibold">Delete tenant</h3>
            </div>
          </template>

          <p class="text-sm text-muted">
            Delete tenant "{{ deletingTenant?.name }}"? The complete database including users,
            collections, and data will be permanently removed.
          </p>

          <UAlert
            v-if="isDeletingDefaultTenant"
            class="mt-3"
            color="warning"
            variant="soft"
            icon="i-lucide-triangle-alert"
            title="This is the configured default tenant"
            description="Deleting it clears the default tenant setting. Guests and superadmins without an active tenant will have no fallback until you pick a new one under Settings."
          />

          <template #footer>
            <div class="flex justify-end gap-2">
              <UButton variant="ghost" color="neutral" @click="deleteOpen = false">Cancel</UButton>
              <UButton
                color="error"
                :loading="deleting"
                icon="i-lucide-trash-2"
                @click="deleteTenantAction"
              >
                Delete tenant
              </UButton>
            </div>
          </template>
        </UCard>
      </template>
    </UModal>
  </div>
</template>
