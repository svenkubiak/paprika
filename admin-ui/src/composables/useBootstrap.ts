import { ref, readonly } from 'vue'
import { api } from '@/lib/api'
import type { BootstrapData } from '@/types'

const bootstrap = ref<BootstrapData | null>(null)
const loading = ref(false)
const loaded = ref(false)
const pendingDefaultTenantNotice = ref<string | null>(null)

export function useBootstrap() {
  async function load(force = false) {
    if (loaded.value && !force) {
      return bootstrap.value
    }

    loading.value = true
    try {
      bootstrap.value = await api.bootstrap()
      loaded.value = true
      if (bootstrap.value.defaultTenantApplied && bootstrap.value.activeTenant) {
        pendingDefaultTenantNotice.value = bootstrap.value.activeTenant.name
      }
      return bootstrap.value
    } finally {
      loading.value = false
    }
  }

  function consumeDefaultTenantNotice() {
    const name = pendingDefaultTenantNotice.value
    pendingDefaultTenantNotice.value = null
    return name
  }

  function reset() {
    bootstrap.value = null
    loaded.value = false
    pendingDefaultTenantNotice.value = null
  }

  return {
    bootstrap: readonly(bootstrap),
    loading: readonly(loading),
    loaded: readonly(loaded),
    load,
    consumeDefaultTenantNotice,
    reset
  }
}
