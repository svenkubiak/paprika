<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useColorMode } from '@vueuse/core'
import { useBootstrap } from '@/composables/useBootstrap'
import { useSidebar } from '@/composables/useSidebar'

const route = useRoute()
const { bootstrap } = useBootstrap()
const { openMobileSidebar } = useSidebar()
const colorMode = useColorMode()

const collectionName = computed(() =>
  route.params.collection ? String(route.params.collection) : ''
)

const pageTitle = computed(() => {
  if (route.name === 'dashboard') return 'Overview'
  if (route.name === 'tenants') return 'Tenants'
  if (route.name === 'settings') return 'Settings'
  if (route.name === 'backup') return 'Backup'
  if (route.name === 'global-hooks') return 'Global hooks'
  if (route.name === 'request-logs') return 'Logs'
  if (route.name === 'tenant-users') return 'Users'
  if (route.name === 'user-settings') return 'Auth'
  if (collectionName.value) return collectionName.value
  return 'Paprika'
})

const showCollectionBreadcrumb = computed(
  () => !!collectionName.value && !!bootstrap.value?.activeTenant
)

const collectionTabs = computed(() => {
  const collection = route.params.collection
  if (!collection) return []

  const name = String(collection)
  return [
    { label: 'Data', icon: 'i-lucide-table-2', to: `/admin/collections/${name}/data` },
    { label: 'Schema', icon: 'i-lucide-columns-3', to: `/admin/collections/${name}/schema` },
    { label: 'Rules', icon: 'i-lucide-shield', to: `/admin/collections/${name}/rules` },
    { label: 'Hooks', icon: 'i-lucide-webhook', to: `/admin/collections/${name}/hooks` },
    { label: 'API', icon: 'i-lucide-code', to: `/admin/collections/${name}/api` }
  ]
})

function toggleColorMode() {
  colorMode.value = colorMode.value === 'dark' ? 'light' : 'dark'
}
</script>

<template>
  <header class="sticky top-0 z-10 border-b border-default bg-default/95 backdrop-blur supports-[backdrop-filter]:bg-default/80">
    <div class="flex items-center justify-between gap-4 px-4 py-3 md:px-6">
      <div class="flex min-w-0 items-center gap-3">
        <UButton
          icon="i-lucide-menu"
          variant="ghost"
          color="neutral"
          class="md:hidden"
          @click="openMobileSidebar($event)"
        />

        <div class="min-w-0">
          <h1
            v-if="showCollectionBreadcrumb"
            class="flex min-w-0 items-center gap-1.5 truncate text-lg font-semibold tracking-tight"
          >
            <span class="truncate">{{ bootstrap?.activeTenant?.name }}</span>
            <UIcon name="i-lucide-chevron-right" class="size-4 shrink-0 text-muted" />
            <span class="truncate">{{ collectionName }}</span>
          </h1>
          <h1 v-else class="truncate text-lg font-semibold tracking-tight">{{ pageTitle }}</h1>
          <p v-if="route.name === 'dashboard'" class="text-sm text-muted">
            Backend status at a glance
          </p>
          <p v-else-if="route.name === 'tenants'" class="text-sm text-muted">
            Manage isolated tenant databases
          </p>
          <p v-else-if="route.name === 'settings'" class="text-sm text-muted">
            Application preferences and configuration
          </p>
          <p v-else-if="route.name === 'backup'" class="text-sm text-muted">
            Export and restore the full instance backup
          </p>
          <p v-else-if="route.name === 'global-hooks'" class="text-sm text-muted">
            Tenant-wide beforeRequest hooks for collections and auth flows
          </p>
          <p v-else-if="route.name === 'request-logs'" class="text-sm text-muted">
            Tenant API request history without payloads
          </p>
          <p v-else-if="route.name === 'tenant-users'" class="text-sm text-muted">
            Manage tenant users and self-registration
          </p>
          <p v-else-if="route.name === 'user-settings'" class="text-sm text-muted">
            Self-registration, password reset, and email verification for tenant users
          </p>
        </div>
      </div>

      <div class="flex items-center gap-2">
        <div class="hidden items-center gap-4 text-sm text-muted lg:flex">
          <div class="flex items-center gap-2">
            <span class="size-2 rounded-full bg-success" />
            DB Connected
          </div>
          <div class="flex items-center gap-2">
            <span class="size-2 rounded-full bg-success" />
            API Healthy
          </div>
        </div>

        <UButton
          :icon="colorMode === 'dark' ? 'i-lucide-sun' : 'i-lucide-moon'"
          variant="ghost"
          color="neutral"
          aria-label="Toggle color mode"
          @click="toggleColorMode"
        />
      </div>
    </div>

    <div v-if="collectionTabs.length" class="border-t border-default px-4 md:px-6">
      <nav class="flex gap-1 overflow-x-auto py-2">
        <UButton
          v-for="tab in collectionTabs"
          :key="tab.to"
          :to="tab.to"
          :icon="tab.icon"
          size="sm"
          :variant="route.path === tab.to ? 'soft' : 'ghost'"
          :color="route.path === tab.to ? 'primary' : 'neutral'"
        >
          {{ tab.label }}
        </UButton>
      </nav>
    </div>
  </header>
</template>
