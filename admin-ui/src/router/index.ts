import { createRouter, createWebHistory } from 'vue-router'
import { useBootstrap } from '@/composables/useBootstrap'
import { isStaleBuildError, reloadForStaleBuild, renderStaleBuildNotice } from '@/lib/stale-build'

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
      meta: { public: true, title: 'Initial setup' },
      // The token only ever travels in the fragment, so it is also the only thing that tells a
      // real invite apart from someone just opening /setup - the server never sees it and can
      // not make this call. Whether the initial setup is done is deliberately not the criterion:
      // superadmin invites reuse this flow long after that point.
      beforeEnter: (to) => {
        const token = new URLSearchParams(to.hash.slice(1)).get('token')
        return token ? true : { name: 'login' }
      }
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
      path: '/admin/user-settings',
      name: 'user-settings',
      component: () => import('@/pages/UserSettingsPage.vue'),
      meta: { title: 'User settings', requiresTenant: true }
    },
    {
      path: '/admin/global-hooks',
      name: 'global-hooks',
      component: () => import('@/pages/GlobalHooksPage.vue'),
      meta: { title: 'Global hooks', requiresTenant: true, requiresSuperAdmin: true }
    },
    {
      path: '/admin/tenant-settings',
      name: 'tenant-settings',
      component: () => import('@/pages/TenantSettingsPage.vue'),
      meta: { title: 'Schema', requiresTenant: true, requiresSuperAdmin: true }
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

router.onError((error, to) => {
  if (!isStaleBuildError(error)) {
    return
  }

  if (reloadForStaleBuild(to.fullPath)) {
    return
  }

  // The reload already happened and the chunk is still not there, so this is not going to fix
  // itself. Say so instead of leaving whatever the failed navigation left behind - on the first
  // navigation of a tab that is an empty #app, i.e. a white page.
  renderStaleBuildNotice(error)
})

// Vite raises this for a modulepreload that failed, which happens before the router ever gets to
// import the chunk. It is deliberately not preventDefault()ed: if the reload is used up, the
// error has to keep propagating so it reaches onError above.
window.addEventListener('vite:preloadError', () => {
  reloadForStaleBuild(window.location.pathname + window.location.search)
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
