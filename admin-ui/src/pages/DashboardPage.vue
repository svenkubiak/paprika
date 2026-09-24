<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useBootstrap } from '@/composables/useBootstrap'
import { api } from '@/lib/api'

const { bootstrap, load } = useBootstrap()
const twoFactorEnabled = ref<boolean | null>(null)

onMounted(async () => {
  const data = await load()
  if (data?.isSuperAdmin) {
    try {
      twoFactorEnabled.value = (await api.getProfile()).twoFactorEnabled
    } catch {
      twoFactorEnabled.value = null
    }
  }
})

const stats = computed(() => bootstrap.value?.stats)
const serverErrors = computed(() => stats.value?.serverErrors24h ?? 0)
const mailDependentTenants = computed(() => bootstrap.value?.warnings?.mailDependentTenants ?? [])
const degradedIndexTenants = computed(() => bootstrap.value?.warnings?.degradedIndexTenants ?? [])

/** Keeps a warning readable on an instance with many tenants. */
function tenantList(names: readonly string[]): string {
  if (names.length <= 3) {
    return names.join(', ')
  }
  return `${names.slice(0, 3).join(', ')} and ${names.length - 3} more`
}

function formatUptime(seconds: number | undefined): string {
  if (seconds === undefined || seconds < 0) return '—'
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const parts: string[] = []
  if (days) parts.push(`${days}d`)
  if (hours) parts.push(`${hours}h`)
  if (minutes || parts.length === 0) parts.push(`${minutes}m`)
  return parts.join(' ')
}

const statCards = computed(() => [
  {
    label: 'Uptime',
    value: formatUptime(stats.value?.uptimeSeconds),
    icon: 'i-lucide-clock',
    color: 'primary'
  },
  {
    label: 'Tenants',
    value: String(stats.value?.tenants ?? 0),
    icon: 'i-lucide-building-2',
    color: 'primary'
  },
  {
    label: 'Collections',
    value: String(stats.value?.collections ?? 0),
    icon: 'i-lucide-layers',
    color: 'primary'
  },
  {
    label: 'Records',
    value: String(stats.value?.records ?? 0),
    icon: 'i-lucide-file-text',
    color: 'primary'
  },
  {
    label: 'Errors (24h)',
    value: String(serverErrors.value),
    icon: 'i-lucide-triangle-alert',
    color: serverErrors.value > 0 ? 'error' : 'primary'
  }
])
</script>

<template>
  <div class="space-y-6">
    <UCard
      v-if="bootstrap?.isSuperAdmin && twoFactorEnabled === false"
      variant="subtle"
      :ui="{ body: 'flex flex-col gap-3 p-4 sm:flex-row sm:items-center sm:justify-between sm:p-4' }"
    >
      <div class="flex items-start gap-3">
        <UIcon name="i-lucide-shield" class="mt-0.5 size-5 shrink-0 text-muted" />
        <div class="space-y-1">
          <p class="text-sm font-medium">Two-factor authentication is not enabled</p>
          <p class="text-sm text-muted">
            Enabling 2FA for the superadmin account is strongly recommended to protect the control
            plane.
          </p>
        </div>
      </div>
      <UButton variant="outline" color="neutral" size="sm" to="/admin/profile" class="shrink-0">
        Enable 2FA
      </UButton>
    </UCard>

    <UAlert
      v-if="bootstrap && !bootstrap.hasActiveTenant && bootstrap.isSuperAdmin"
      color="warning"
      variant="soft"
      icon="i-lucide-building-2"
      title="No tenant selected"
      description="Create or choose a tenant before managing collections and data."
    >
      <template #actions>
        <UButton color="warning" variant="soft" to="/admin/tenants">Manage tenants</UButton>
      </template>
    </UAlert>

    <!-- Uniqueness that is not enforced is invisible until two definitions share a name and an
         edit lands on whichever one MongoDB returns first. Only a restored archive gets an
         instance into this state, and only the startup log said so until now. -->
    <UAlert
      v-if="degradedIndexTenants.length"
      color="error"
      variant="soft"
      icon="i-lucide-database-zap"
      title="Collection definitions are not protected by a unique index"
      :description="`${tenantList(degradedIndexTenants)} had duplicate collection definitions when the index was created, so duplicate names and ids are no longer rejected. Remove the duplicates and restart to enforce uniqueness.`"
    />

    <!-- The feature is switched on, the API accepts the request, and the mail is dropped. There
         is nothing in the UI that would otherwise tell an admin about it. -->
    <UAlert
      v-if="mailDependentTenants.length"
      color="warning"
      variant="soft"
      icon="i-lucide-mail-warning"
      title="Emails cannot be delivered"
      :description="`Password reset or email verification is enabled for ${tenantList(mailDependentTenants)}, but the instance has no SMTP host configured. Those emails are never sent.`"
    >
      <template #actions>
        <UButton color="warning" variant="soft" to="/admin/user-settings">User settings</UButton>
      </template>
    </UAlert>

    <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <UCard v-for="card in statCards" :key="card.label">
        <div class="flex items-start justify-between gap-3">
          <div>
            <p class="text-sm font-medium text-muted">{{ card.label }}</p>
            <p class="mt-2 text-2xl font-semibold tracking-tight">{{ card.value }}</p>
          </div>
          <div
            class="flex size-10 items-center justify-center rounded-lg"
            :class="{
              'bg-success/10 text-success': card.color === 'success',
              'bg-error/10 text-error': card.color === 'error',
              'bg-primary/10 text-primary': card.color === 'primary'
            }"
          >
            <UIcon :name="card.icon" class="size-5" />
          </div>
        </div>
      </UCard>
    </div>
  </div>
</template>
