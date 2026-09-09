<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import OverlayDrawer from '@/components/OverlayDrawer.vue'
import AppLogo from '@/components/AppLogo.vue'
import { useBootstrap } from '@/composables/useBootstrap'
import { useAppToast } from '@/composables/useAppToast'
import { useSidebar } from '@/composables/useSidebar'
import { api } from '@/lib/api'

const route = useRoute()
const router = useRouter()
const toast = useAppToast()
const { bootstrap, load, reset } = useBootstrap()
const { mobileSidebarOpen, closeMobileSidebar } = useSidebar()

let removeAfterEach: (() => void) | undefined

onMounted(() => {
  removeAfterEach = router.afterEach(() => {
    closeMobileSidebar()
  })
})

onUnmounted(() => {
  removeAfterEach?.()
})

const tenantItems = computed(() =>
  (bootstrap.value?.tenants || []).map((tenant) => ({
    label: `${tenant.name} (${tenant.slug})`,
    value: tenant.id
  }))
)

const activeTenantId = computed(() => bootstrap.value?.activeTenant?.id || '')

const generalNavItems = computed(() => {
  const items = [
    {
      label: 'Overview',
      icon: 'i-lucide-layout-dashboard',
      to: '/',
      active: route.name === 'dashboard'
    }
  ]

  if (bootstrap.value?.isSuperAdmin) {
    items.push({
      label: 'Tenants',
      icon: 'i-lucide-building-2',
      to: '/admin/tenants',
      active: route.name === 'tenants'
    })
  }

  if (bootstrap.value?.isSuperAdmin) {
    items.push({
      label: 'Backup',
      icon: 'i-lucide-archive',
      to: '/admin/backup',
      active: route.name === 'backup'
    })
  }

  if (bootstrap.value?.isSuperAdmin) {
    items.push({
      label: 'Superadmins',
      icon: 'i-lucide-shield',
      to: '/admin/superadmins',
      active: route.name === 'superadmins'
    })
  }

  items.push({
    label: 'Settings',
    icon: 'i-lucide-settings',
    to: '/admin/settings',
    active: route.name === 'settings'
  })

  return items
})

const tenantNavItems = computed(() => {
  const items = []

  if (bootstrap.value?.isSuperAdmin) {
    items.push({
      label: 'Users',
      icon: 'i-lucide-users',
      to: '/admin/collections/users/data',
      active: route.params.collection === 'users'
    })

    items.push({
      label: 'Auth',
      icon: 'i-lucide-user-cog',
      to: '/admin/user-settings',
      active: route.name === 'user-settings'
    })
  }

  items.push({
    label: 'Logs',
    icon: 'i-lucide-scroll-text',
    to: '/admin/logs',
    active: route.name === 'request-logs'
  })

  if (bootstrap.value?.isSuperAdmin) {
    items.push({
      label: 'Global hooks',
      icon: 'i-lucide-webhook',
      to: '/admin/global-hooks',
      active: route.name === 'global-hooks'
    })
  }

  return items
})

async function onTenantChange(tenantId: string) {
  try {
    await api.switchTenant(tenantId)
    await load(true)
    if (
      route.name === 'request-logs'
      || route.name === 'tenant-users'
      || route.name === 'global-hooks'
      || String(route.name || '').startsWith('collection-')
    ) {
      return
    }
    await router.push('/')
    toast.add({
      title: tenantId ? 'Tenant switched' : 'Tenant cleared',
      color: 'success',
      icon: 'i-lucide-circle-check'
    })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to switch tenant',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  }
}

function openNewCollection() {
  window.dispatchEvent(new CustomEvent('paprika:new-collection'))
  closeMobileSidebar()
}

async function logout() {
  try {
    await api.logout()
    reset()
    closeMobileSidebar()
    await router.push('/login')
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to log out',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  }
}
</script>

<template>
  <aside class="hidden h-full w-64 shrink-0 border-r border-default bg-default md:flex md:flex-col">
    <SidebarContent
      :bootstrap="bootstrap"
      :general-nav-items="generalNavItems"
      :tenant-nav-items="tenantNavItems"
      :tenant-items="tenantItems"
      :active-tenant-id="activeTenantId"
      :active-collection="String(route.params.collection || '')"
      @tenant-change="onTenantChange"
      @new-collection="openNewCollection"
      @logout="logout"
    />
  </aside>

  <OverlayDrawer
    v-model:open="mobileSidebarOpen"
    side="left"
    width-class="w-full max-w-xs"
    overlay-class="md:hidden"
    labelled-by="mobile-sidebar-title"
  >
    <template #default="{ close }">
      <div class="flex items-center justify-between border-b border-default p-4">
        <div id="mobile-sidebar-title">
          <AppLogo size="sm" show-text />
        </div>
        <UButton icon="i-lucide-x" variant="ghost" color="neutral" @click="close" />
      </div>
      <SidebarContent
        class="min-h-0 flex-1"
        :bootstrap="bootstrap"
        :general-nav-items="generalNavItems"
      :tenant-nav-items="tenantNavItems"
        :tenant-items="tenantItems"
        :active-tenant-id="activeTenantId"
        :active-collection="String(route.params.collection || '')"
        mobile
        @tenant-change="onTenantChange"
        @new-collection="openNewCollection"
        @logout="logout"
      />
    </template>
  </OverlayDrawer>
</template>
