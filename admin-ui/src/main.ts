import './assets/css/main.css'

import { createApp } from 'vue'
import ui from '@nuxt/ui/vue-plugin'
import App from './App.vue'
import router from './router'
import { clearStaleBuildReload, renderStaleBuildNotice } from '@/lib/stale-build'

const app = createApp(App)

app.use(router)
app.use(ui)

// Mounting waits for the initial navigation so the first paint is the real page instead of an
// empty shell. The rejection handler is not optional: without it a failed initial navigation
// never mounts anything and the user is left staring at a white page.
void router.isReady().then(
  () => {
    clearStaleBuildReload()
    app.mount('#app')
  },
  (error: unknown) => {
    renderStaleBuildNotice(error)
  }
)
