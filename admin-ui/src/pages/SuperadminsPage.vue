<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '@/lib/api'
import { modalUi } from '@/lib/overlay-ui'
import { useBootstrap } from '@/composables/useBootstrap'
import { useAppToast } from '@/composables/useAppToast'
import type { SuperadminInvite, SuperadminSummary } from '@/types'

const toast = useAppToast()
const { bootstrap } = useBootstrap()

const superadmins = ref<SuperadminSummary[]>([])
const loading = ref(true)

const inviteOpen = ref(false)
const inviteUsername = ref('')
const inviteEmail = ref('')
const inviting = ref(false)
const invite = ref<SuperadminInvite | null>(null)
const sendingInvite = ref(false)
const inviteEmailed = ref(false)

const smtpConfigured = computed(() => bootstrap.value?.smtpConfigured === true)

const deleteOpen = ref(false)
const deleting = ref(false)
const deletingAdmin = ref<SuperadminSummary | null>(null)

const currentAdminId = computed(() => bootstrap.value?.adminId || null)

const columns = [
  { accessorKey: 'username', header: 'Username' },
  { accessorKey: 'email', header: 'Email' },
  { accessorKey: 'status', header: 'Status' },
  { accessorKey: 'twoFactor', header: '2FA' },
  { id: 'actions', header: 'Actions' }
]

const rows = computed(() => superadmins.value)

const inviteLink = computed(() =>
  invite.value ? `${window.location.origin}${invite.value.setupPath}` : ''
)

onMounted(refresh)

async function refresh() {
  loading.value = true
  try {
    superadmins.value = await api.listSuperadmins()
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to load superadmins',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    loading.value = false
  }
}

function openInvite() {
  inviteUsername.value = ''
  inviteEmail.value = ''
  invite.value = null
  inviteEmailed.value = false
  inviteOpen.value = true
}

async function createInvite() {
  const username = inviteUsername.value.trim()
  if (!username) {
    toast.add({ title: 'Username is required', color: 'error', icon: 'i-lucide-circle-x' })
    return
  }

  inviting.value = true
  try {
    invite.value = await api.inviteSuperadmin(username, inviteEmail.value.trim() || null)
    await refresh()
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to create invite',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    inviting.value = false
  }
}

async function copyInviteLink() {
  try {
    await navigator.clipboard.writeText(inviteLink.value)
    toast.add({ title: 'Invite link copied', color: 'success', icon: 'i-lucide-copy' })
  } catch {
    toast.add({ title: 'Could not copy to clipboard', color: 'error', icon: 'i-lucide-circle-x' })
  }
}

async function sendInviteEmail() {
  const current = invite.value
  const email = inviteEmail.value.trim()
  if (!current || !email) return

  sendingInvite.value = true
  try {
    await api.emailSuperadminInvite(current.token, email, current.username)
    inviteEmailed.value = true
    toast.add({ title: `Invite emailed to ${email}`, color: 'success', icon: 'i-lucide-mail' })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to send invite email',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    sendingInvite.value = false
  }
}

function confirmDelete(admin: SuperadminSummary) {
  deletingAdmin.value = admin
  deleteOpen.value = true
}

async function deleteAdminAction() {
  if (!deletingAdmin.value) return
  deleting.value = true
  try {
    await api.deleteSuperadmin(deletingAdmin.value.id)
    deleteOpen.value = false
    await refresh()
    toast.add({ title: 'Superadmin removed', color: 'success', icon: 'i-lucide-circle-check' })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to remove superadmin',
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
    <div class="flex items-start justify-between gap-4">
      <p class="max-w-2xl text-sm text-muted">
        Superadmins operate the whole Paprika instance. Add another one by invite: create a
        one-time setup link, then send it to the person out of band. They pick their own password
        when they open it.
      </p>
      <UButton icon="i-lucide-plus" class="shrink-0" @click="openInvite">New superadmin</UButton>
    </div>

    <UCard :ui="{ body: 'p-0 sm:p-0' }">
      <UTable :data="rows" :columns="columns" :loading="loading">
        <template #username-cell="{ row }">
          <div class="flex items-center gap-2">
            <span class="font-medium">{{ row.original.username }}</span>
            <UBadge
              v-if="row.original.id === currentAdminId"
              color="primary"
              variant="soft"
              size="sm"
            >
              You
            </UBadge>
          </div>
        </template>
        <template #email-cell="{ row }">
          <span class="text-sm text-muted">{{ row.original.email || '—' }}</span>
        </template>
        <template #status-cell="{ row }">
          <UBadge :color="row.original.pending ? 'warning' : 'success'" variant="soft">
            {{ row.original.pending ? 'Pending invite' : 'Active' }}
          </UBadge>
        </template>
        <template #twoFactor-cell="{ row }">
          <span v-if="row.original.pending" class="text-muted">—</span>
          <UBadge v-else :color="row.original.twoFactorEnabled ? 'success' : 'neutral'" variant="soft">
            {{ row.original.twoFactorEnabled ? 'On' : 'Off' }}
          </UBadge>
        </template>
        <template #actions-cell="{ row }">
          <div class="flex justify-end gap-2" @click.stop>
            <UButton
              size="sm"
              color="error"
              variant="soft"
              icon="i-lucide-trash-2"
              :disabled="row.original.id === currentAdminId"
              @click="confirmDelete(row.original)"
            >
              {{ row.original.pending ? 'Revoke' : 'Remove' }}
            </UButton>
          </div>
        </template>
        <template #empty>
          <div class="py-10 text-center text-muted">No superadmins found.</div>
        </template>
      </UTable>
    </UCard>

    <UModal v-model:open="inviteOpen" portal="body" :ui="modalUi">
      <template #content>
        <UCard>
          <template #header>
            <div class="flex items-center gap-2">
              <UIcon name="i-lucide-shield" class="size-5" />
              <h3 class="font-semibold">Invite superadmin</h3>
            </div>
          </template>

          <div v-if="!invite" class="space-y-4">
            <UFormField label="Username" required class="w-full">
              <UInput
                v-model="inviteUsername"
                class="w-full"
                type="text"
                icon="i-lucide-user"
                autofocus
                @keyup.enter="createInvite"
              />
            </UFormField>
            <UFormField
              label="Email"
              hint="Optional"
              help="Stored with the account. You can email the setup link to this address on the next step."
              class="w-full"
            >
              <UInput v-model="inviteEmail" class="w-full" type="email" icon="i-lucide-mail" />
            </UFormField>
            <UAlert
              color="neutral"
              variant="soft"
              icon="i-lucide-clock"
              title="The link expires after 30 minutes"
              description="Once used it is invalidated. If it expires, create a new invite."
            />
          </div>

          <div v-else class="space-y-4">
            <UAlert
              color="success"
              variant="soft"
              icon="i-lucide-circle-check"
              :title="`Invite created for ${invite.username}`"
              description="Copy the link below and send it to the new superadmin. It is shown only once."
            />
            <UFormField label="Setup link" class="w-full">
              <div class="flex gap-2">
                <UInput :model-value="inviteLink" readonly class="w-full font-mono text-sm" />
                <UButton
                  color="neutral"
                  variant="soft"
                  icon="i-lucide-copy"
                  class="shrink-0"
                  @click="copyInviteLink"
                >
                  Copy
                </UButton>
              </div>
            </UFormField>

            <UFormField
              v-if="smtpConfigured"
              label="Send by email"
              help="Paprika emails the setup link over the instance SMTP settings."
              class="w-full"
            >
              <div class="flex gap-2">
                <UInput
                  v-model="inviteEmail"
                  type="email"
                  placeholder="name@example.com"
                  icon="i-lucide-mail"
                  class="w-full"
                />
                <UButton
                  color="neutral"
                  variant="soft"
                  icon="i-lucide-mail"
                  class="shrink-0"
                  :loading="sendingInvite"
                  :disabled="!inviteEmail.trim() || inviteEmailed"
                  @click="sendInviteEmail"
                >
                  {{ inviteEmailed ? 'Sent' : 'Send' }}
                </UButton>
              </div>
            </UFormField>
            <p class="text-sm text-muted">
              The invitee opens this link, chooses a password of at least 16 characters, and is
              signed in as a superadmin. Until then the account shows as a pending invite.
            </p>
          </div>

          <template #footer>
            <div class="flex justify-end gap-2">
              <UButton variant="ghost" color="neutral" @click="inviteOpen = false">
                {{ invite ? 'Done' : 'Cancel' }}
              </UButton>
              <UButton
                v-if="!invite"
                :loading="inviting"
                :disabled="!inviteUsername.trim()"
                icon="i-lucide-link"
                @click="createInvite"
              >
                Create invite link
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
              <h3 class="font-semibold">
                {{ deletingAdmin?.pending ? 'Revoke invite' : 'Remove superadmin' }}
              </h3>
            </div>
          </template>

          <p class="text-sm text-muted">
            <template v-if="deletingAdmin?.pending">
              Revoke the pending invite for "{{ deletingAdmin?.username }}"? The setup link stops
              working immediately.
            </template>
            <template v-else>
              Remove superadmin "{{ deletingAdmin?.username }}"? They lose access to the admin
              control plane right away. This cannot be undone.
            </template>
          </p>

          <template #footer>
            <div class="flex justify-end gap-2">
              <UButton variant="ghost" color="neutral" @click="deleteOpen = false">Cancel</UButton>
              <UButton
                color="error"
                :loading="deleting"
                icon="i-lucide-trash-2"
                @click="deleteAdminAction"
              >
                {{ deletingAdmin?.pending ? 'Revoke invite' : 'Remove superadmin' }}
              </UButton>
            </div>
          </template>
        </UCard>
      </template>
    </UModal>
  </div>
</template>
