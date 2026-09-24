<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useColorMode } from '@vueuse/core'
import { useBootstrap } from '@/composables/useBootstrap'
import { useGeneralNav } from '@/composables/useGeneralNav'
import { useSidebar } from '@/composables/useSidebar'

const route = useRoute()
const { bootstrap } = useBootstrap()
const { generalNavItems } = useGeneralNav()
const { openMobileSidebar } = useSidebar()
const colorMode = useColorMode()

const collectionName = computed(() =>
  route.params.collection ? String(route.params.collection) : ''
)

const pageTitle = computed(() => {
  if (route.name === 'dashboard') return 'Overview'
  if (route.name === 'tenants') return 'Tenants'
  if (route.name === 'settings') return 'Settings'
  if (route.name === 'profile') return 'Profile'
  if (route.name === 'backup') return 'Backup'
  if (route.name === 'global-hooks') return 'Global hooks'
  if (route.name === 'request-logs') return 'Logs'
  if (route.name === 'user-settings') return 'Auth'
  if (route.name === 'superadmins') return 'Superadmins'
  if (route.name === 'tenant-settings') return 'Schema import & export'
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
            <template v-if="bootstrap?.activeTenant">
              API requests for
              <span class="font-medium text-default">{{ bootstrap.activeTenant.name }}</span>
              — no payloads
            </template>
            <template v-else>Select a tenant to view request logs</template>
          </p>
          <p v-else-if="route.name === 'superadmins'" class="text-sm text-muted">
            Manage superadmin accounts and invites
          </p>
          <p v-else-if="route.name === 'profile'" class="text-sm text-muted">
            Your own superadmin account
          </p>
          <p v-else-if="route.name === 'user-settings'" class="text-sm text-muted">
            Self-registration, password reset, and email verification for tenant users
          </p>
        </div>
      </div>

      <div class="flex items-center gap-2">
        <!-- Instance-wide navigation used to sit in the sidebar, which is otherwise entirely
             tenant-scoped. It is the only way to reach these pages, so it needs real menu
             semantics: keyboard navigation, Esc, and focus returning to the trigger. -->
        <UDropdownMenu
          :items="generalNavItems"
          :content="{ align: 'end' }"
          :ui="{ content: 'w-56' }"
        >
          <UButton
            icon="i-lucide-sliders-horizontal"
            variant="ghost"
            color="neutral"
            aria-label="Instance navigation"
          />
        </UDropdownMenu>

        <!-- The picture and the name of the account this session belongs to, and the way to the
             page that changes them. Deliberately not a menu: there is exactly one destination. -->
        <UButton
          to="/admin/profile"
          variant="ghost"
          :color="route.name === 'profile' ? 'primary' : 'neutral'"
          class="gap-2"
          aria-label="Your profile"
        >
          <UAvatar
            :src="bootstrap?.adminAvatarUrl || undefined"
            :alt="bootstrap?.adminUsername || 'Profile'"
            icon="i-lucide-user"
            size="2xs"
          />
          <span class="hidden max-w-32 truncate text-sm font-medium sm:inline">
            {{ bootstrap?.adminUsername || 'Profile' }}
          </span>
        </UButton>

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
