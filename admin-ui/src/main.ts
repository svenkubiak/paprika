import './assets/css/main.css'

import { createApp } from 'vue'
import ui from '@nuxt/ui/vue-plugin'
import App from './App.vue'
import router from './router'
import { clearStaleBuildReload, renderStaleBuildNotice } from '@/lib/stale-build'

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

void router.isReady().then(
  () => {
    clearStaleBuildReload()
  },
  (error: unknown) => {
    // onError already handles a stale build by reloading; this only covers the case where that
    // is not what happened, or where the reload did not help.
    renderStaleBuildNotice(error)
  }
)
