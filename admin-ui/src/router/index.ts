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
      // Opened from a mailbox, so it has to work without a session. The token travels in the
      // fragment for the same reason it does on /setup: it never reaches the server as part of a
      // URL, so no access log or proxy can end up holding it.
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

/**
 * Every route component is a lazy import, so a navigation error is - short of a bug in a route
 * guard - always a chunk this build can no longer get hold of. Matching the browser's wording
 * to decide that was tried first and kept missing cases: Safari's "Load failed", the MIME type
 * complaint a proxy's HTML error page produces, a plain network error from a connection that
 * died with the old process. Each miss left the navigation aborted, and on the first navigation
 * of a tab that is an empty #app - a white page. So recovery no longer depends on the wording;
 * the message is only used to pick what the notice says.
 */
router.onError((error, to) => {
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

/**
 * Any request that comes back without a session ends up here. This is a router navigation and not
 * a full page load on purpose - the page load used to race the navigation the guard below starts
 * for the very same reason, and whichever one lost left the app unmounted. Everything the old
 * session put in memory is dropped explicitly instead.
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
 * Until the first navigation has settled there is nothing on screen but the placeholder, so an
 * aborted navigation is a blank page and the notice has to take over. Afterwards the app is
 * rendered and must not be wiped - aborting leaves the user on the page they were on.
 */
let firstNavigationSettled = false
router.afterEach(() => {
  firstNavigationSettled = true
})

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

/**
 * A restart takes the server away for a few seconds. Retrying the bootstrap call over that
 * window is what keeps an open tab from being thrown out of a session that is still valid.
 */
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
      // `reason` is left off here: a guard that runs before anything was ever loaded cannot tell
      // an expired session apart from a bookmark opened in a fresh browser.
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
    // Only an answer from the application can mean "not signed in". A request that never got
    // one means the server is gone, and sending the user to a login page they cannot use (the
    // login call would fail the same way) hides that behind a wrong explanation.
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
