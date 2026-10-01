import { createRouter, createWebHistory } from 'vue-router'
import { useBootstrap } from '@/composables/useBootstrap'
import { isNetworkError, onSessionExpired } from '@/lib/api'
import {
  reloadForStaleBuild,
  renderServerUnreachableNotice,
  renderStaleBuildNotice
} from '@/lib/stale-build'
import type { BootstrapData } from '@/types'

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
      // The fragment token is the only thing that tells a real invite apart; the server never sees
      // it. Setup completion is not the criterion: superadmin invites reuse this flow.
      beforeEnter: (to) => {
        const token = new URLSearchParams(to.hash.slice(1)).get('token')
        return token ? true : { name: 'login' }
      }
    },
    {
      // Works without a session; the fragment keeps the token out of access logs and proxies.
      path: '/verify-email',
      name: 'verify-email',
      component: () => import('@/pages/VerifyEmailPage.vue'),
      meta: { public: true, title: 'Confirm email address' }
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
      path: '/admin/profile',
      name: 'profile',
      component: () => import('@/pages/ProfilePage.vue'),
      meta: { title: 'Profile' }
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
      path: '/admin/users',
      redirect: '/admin/collections/users/data'
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/'
    }
  ]
})

/**
 * Every route component is lazy, so a navigation error is (barring a guard bug) a chunk this build
 * can no longer load. Recovery must not depend on the browser's wording; it varies too much.
 */
router.onError((error, to) => {
  if (reloadForStaleBuild(to.fullPath)) {
    return
  }

  // Reloaded already and the chunk is still missing; show the notice instead of a blank page.
  renderStaleBuildNotice(error)
})

// Raised for a failed modulepreload, before the router imports the chunk. Not preventDefault()ed:
// if the reload is used up, the error must still reach onError above.
window.addEventListener('vite:preloadError', () => {
  reloadForStaleBuild(window.location.pathname + window.location.search)
})

/**
 * A router navigation, not a full page load: a page load raced the guard's navigation and left the
 * app unmounted. Everything the old session put in memory is dropped explicitly instead.
 */
onSessionExpired(() => {
  const { reset } = useBootstrap()
  reset()

  const current = router.currentRoute.value
  if (current.meta.public) {
    return
  }

  void router.replace({
    name: 'login',
    query: { reason: 'expired', redirect: current.fullPath }
  })
})

const NETWORK_RETRY_DELAYS_MS = [400, 1200, 2500]

/**
 * Before the first navigation settles, an aborted one is a blank page and the notice takes over;
 * afterwards the rendered app must not be wiped.
 */
let firstNavigationSettled = false
router.afterEach(() => {
  firstNavigationSettled = true
})

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

/** Retried over a restart window so an open tab is not thrown out of a still-valid session. */
async function loadBootstrap(): Promise<BootstrapData | null> {
  const { load } = useBootstrap()

  for (let attempt = 0; ; attempt++) {
    try {
      return await load()
    } catch (error) {
      if (!isNetworkError(error) || attempt >= NETWORK_RETRY_DELAYS_MS.length) {
        throw error
      }

      await sleep(NETWORK_RETRY_DELAYS_MS[attempt])
    }
  }
}

router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }

  try {
    const data = await loadBootstrap()
    if (!data?.authenticated) {
      // No `reason`: before anything loaded, an expired session looks like a bookmark opened fresh.
      return { name: 'login', query: { redirect: to.fullPath } }
    }

    if (to.meta.requiresSuperAdmin && !data.isSuperAdmin) {
      return { name: 'dashboard' }
    }

    if (to.meta.requiresTenant && !data.hasActiveTenant) {
      return data.isSuperAdmin ? { name: 'tenants' } : { name: 'dashboard' }
    }

    return true
  } catch (error) {
    // Only an application answer can mean "not signed in"; with the server gone the login page
    // would fail the same way and give the wrong explanation.
    if (isNetworkError(error)) {
      if (!firstNavigationSettled) {
        renderServerUnreachableNotice()
      }
      return false
    }

    return { name: 'login', query: { redirect: to.fullPath } }
  }
})

export default router
