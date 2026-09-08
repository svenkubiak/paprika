import './assets/css/main.css'

import { createApp } from 'vue'
import ui from '@nuxt/ui/vue-plugin'
import App from './App.vue'
import router from './router'

const app = createApp(App)

app.use(router)
app.use(ui)

void router.isReady().then(() => {
  sessionStorage.removeItem('paprika:chunk-reload')
  app.mount('#app')
})
