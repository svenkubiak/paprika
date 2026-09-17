import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useBootstrap } from '@/composables/useBootstrap'

export type NavItem = {
  label: string
  icon: string
  to: string
  active: boolean
}

/** Instance-wide navigation, shown in the header menu instead of the tenant-scoped sidebar. */
export function useGeneralNav() {
  const route = useRoute()
  const { bootstrap } = useBootstrap()

  const generalNavItems = computed<NavItem[]>(() => {
    const items: NavItem[] = [
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

      items.push({
        label: 'Backup',
        icon: 'i-lucide-archive',
        to: '/admin/backup',
        active: route.name === 'backup'
      })

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

  return { generalNavItems }
}
