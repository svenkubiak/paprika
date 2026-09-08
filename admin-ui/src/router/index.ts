import { createRouter, createWebHistory } from 'vue-router'
import { useBootstrap } from '@/composables/useBootstrap'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/pages/LoginPage.vue'),
      meta: { public: true, title: 'Sign in' }
    },
    {
      path: '/setup',
      name: 'setup',
      component: () => import('@/pages/SetupPage.vue'),
      meta: { public: true, title: 'Initial setup' }
    },
    {
      path: '/',
      name: 'dashboard',
      component: () => import('@/pages/DashboardPage.vue'),
      meta: { title: 'Overview' }
    },
    {
      path: '/admin/tenants',
      name: 'tenants',
      component: () => import('@/pages/TenantsPage.vue'),
      meta: { title: 'Tenants', requiresSuperAdmin: true }
    },
    {
      path: '/admin/collections/:collection/data',
      name: 'collection-data',
      component: () => import('@/pages/CollectionDataPage.vue'),
      meta: { title: 'Data', requiresTenant: true }
    },
    {
      path: '/admin/collections/:collection/schema',
      name: 'collection-schema',
      component: () => import('@/pages/CollectionSchemaPage.vue'),
      meta: { title: 'Schema', requiresTenant: true }
    },
    {
      path: '/admin/collections/:collection/rules',
      name: 'collection-rules',
      component: () => import('@/pages/CollectionRulesPage.vue'),
      meta: { title: 'Rules', requiresTenant: true }
    },
    {
      path: '/admin/collections/:collection/hooks',
      name: 'collection-hooks',
      component: () => import('@/pages/CollectionHooksPage.vue'),
      meta: { title: 'Hooks', requiresTenant: true }
    },
    {
      path: '/admin/collections/:collection/api',
      name: 'collection-api',
      component: () => import('@/pages/CollectionApiPage.vue'),
      meta: { title: 'API', requiresTenant: true }
    },
    {
      path: '/admin/global-hooks',
      name: 'global-hooks',
      component: () => import('@/pages/GlobalHooksPage.vue'),
      meta: { title: 'Global hooks', requiresTenant: true, requiresSuperAdmin: true }
    },
    {
      path: '/admin/backup',
      name: 'backup',
      component: () => import('@/pages/BackupPage.vue'),
      meta: { title: 'Backup', requiresSuperAdmin: true }
    },
    {
      path: '/admin/superadmins',
      name: 'superadmins',
      component: () => import('@/pages/SuperadminsPage.vue'),
      meta: { title: 'Superadmins', requiresSuperAdmin: true }
    },
    {
      path: '/admin/settings',
      name: 'settings',
      component: () => import('@/pages/SettingsPage.vue'),
      meta: { title: 'Settings' }
    },
    {
      path: '/admin/logs',
      name: 'request-logs',
      component: () => import('@/pages/RequestLogsPage.vue'),
      meta: { title: 'Logs' }
    },
    {
      // The tenant users collection now uses the generic collection views (Data/Schema/Rules/
      // Hooks/API). The Data tab renders the dedicated user management UI.
      path: '/admin/users',
      redirect: '/admin/collections/users/data'
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/'
    }
  ]
})

const CHUNK_RELOAD_KEY = 'paprika:chunk-reload'

router.onError((error, to) => {
  const message = error instanceof Error ? error.message : String(error)
  const isChunkLoadFailure =
    message.includes('Failed to fetch dynamically imported module') ||
    message.includes('Importing a module script failed') ||
    message.includes('error loading dynamically imported module')

  if (!isChunkLoadFailure) {
    return
  }

  if (!sessionStorage.getItem(CHUNK_RELOAD_KEY)) {
    sessionStorage.setItem(CHUNK_RELOAD_KEY, '1')
    window.location.assign(to.fullPath)
    return
  }

  sessionStorage.removeItem(CHUNK_RELOAD_KEY)
})

router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }

  try {
    const { load } = useBootstrap()
    const data = await load()
    if (!data?.authenticated) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }

    if (to.meta.requiresSuperAdmin && !data.isSuperAdmin) {
      return { name: 'dashboard' }
    }

    if (to.meta.requiresTenant && !data.hasActiveTenant) {
      return data.isSuperAdmin ? { name: 'tenants' } : { name: 'dashboard' }
    }

    return true
  } catch {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
})

export default router
