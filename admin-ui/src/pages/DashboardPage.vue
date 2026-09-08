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
      const settings = await api.getSettings()
      twoFactorEnabled.value = settings.twoFactorEnabled
    } catch {
      twoFactorEnabled.value = null
    }
  }
})

const stats = computed(() => bootstrap.value?.stats)

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
    label: 'Database',
    value: stats.value?.connected ? 'Connected' : 'Disconnected',
    icon: 'i-lucide-database',
    color: stats.value?.connected ? 'success' : 'error'
  },
  {
    label: 'API',
    value: stats.value?.healthy ? 'Healthy' : 'Unhealthy',
    icon: 'i-lucide-activity',
    color: stats.value?.healthy ? 'success' : 'error'
  },
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
      <UButton variant="outline" color="neutral" size="sm" to="/admin/settings" class="shrink-0">
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
