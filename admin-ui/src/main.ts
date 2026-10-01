import './assets/css/main.css'

import { createApp } from 'vue'
import ui from '@nuxt/ui/vue-plugin'
import App from './App.vue'
import router from './router'
import { clearStaleBuildReload, isReloadPending, renderStaleBuildNotice } from '@/lib/stale-build'

/** Installed by public/boot.js, which runs before this bundle. */
declare global {
  interface Window {
    __paprikaBoot?: { mounted: boolean; clearReloadBudget?: () => void }
  }
}

const app = createApp(App)

app.use(router)
app.use(ui)

// Otherwise a render error tears down the component tree and leaves a blank page.
app.config.errorHandler = (error, _instance, info) => {
  console.error(`[paprika] unhandled error (${info})`, error)
}

// Mounted before the initial navigation resolves on purpose: gating on isReady() turned every
// failed navigation (redirect, aborted lazy import, rejected bootstrap) into a blank page.
app.mount('#app')

// Tells the boot watchdog the bundle is alive. Set after mount, not after the first navigation:
// from here on something is rendered and failures can be reported.
if (window.__paprikaBoot) {
  window.__paprikaBoot.mounted = true
}

void router.isReady().then(
  () => {
    clearStaleBuildReload()
  },
  (error: unknown) => {
    // onError already reloads on a stale build; this covers it not happening or not helping.
    if (!isReloadPending()) {
      renderStaleBuildNotice(error)
    }
  }
)
