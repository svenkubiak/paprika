import './assets/css/main.css'

import { createApp } from 'vue'
import ui from '@nuxt/ui/vue-plugin'
import App from './App.vue'
import router from './router'
import { clearStaleBuildReload, isReloadPending, renderStaleBuildNotice } from '@/lib/stale-build'

/** Installed by public/boot.js, which runs before this bundle - see the comment over there. */
declare global {
  interface Window {
    __paprikaBoot?: { mounted: boolean; clearReloadBudget?: () => void }
  }
}

const app = createApp(App)

app.use(router)
app.use(ui)

// A render error would otherwise tear down the component tree and leave the page blank without
// a word about why.
app.config.errorHandler = (error, _instance, info) => {
  console.error(`[paprika] unhandled error (${info})`, error)
}

// Mounted before the initial navigation resolves on purpose. Gating the mount on isReady() made
// every way that navigation can fail - a guard that redirects while the document is already
// being replaced, an aborted lazy import, a rejected bootstrap - come out as an empty
// <div id="app">, i.e. a white page with nothing to act on.
app.mount('#app')

// Tells the boot watchdog that the bundle is alive, so its timeout stops treating this tab as a
// failed boot. Set right after mount and not after the first navigation: from here on there is
// something rendered (the placeholder in App.vue) and code that can report its own failures.
if (window.__paprikaBoot) {
  window.__paprikaBoot.mounted = true
}

void router.isReady().then(
  () => {
    clearStaleBuildReload()
  },
  (error: unknown) => {
    // onError already handles a stale build by reloading; this only covers the case where that
    // is not what happened, or where the reload did not help.
    if (!isReloadPending()) {
      renderStaleBuildNotice(error)
    }
  }
)
