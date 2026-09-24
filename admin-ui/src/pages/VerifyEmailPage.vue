<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AppLogo from '@/components/AppLogo.vue'
import { api } from '@/lib/api'

type State = 'working' | 'confirmed' | 'failed'

const state = ref<State>('working')
const username = ref('')
const error = ref('')

// The link is opened from a mailbox, so the confirmation runs on its own without anything to
// click: the token is lifted out of the fragment, spent right away and removed from the address
// bar, so a bookmark or a shared screenshot of this page carries nothing usable.
onMounted(async () => {
  const token = new URLSearchParams(window.location.hash.slice(1)).get('token') || ''
  window.history.replaceState(window.history.state, '', '/verify-email')

  if (!token) {
    state.value = 'failed'
    error.value = 'This confirmation link is incomplete.'
    return
  }

  try {
    const result = await api.confirmProfileEmail(token)
    username.value = result.username || ''
    state.value = 'confirmed'
  } catch (err) {
    error.value = err instanceof Error ? err.message : 'This confirmation link is invalid or has expired.'
    state.value = 'failed'
  }
})
</script>

<template>
  <div class="flex min-h-svh flex-col items-center justify-center gap-6 bg-muted/30 p-4">
    <AppLogo size="lg" show-text />

    <UCard class="w-full max-w-md">
      <template #header>
        <div>
          <h1 class="text-lg font-semibold tracking-tight">Confirm email address</h1>
          <p class="text-sm text-muted">Superadmin account</p>
        </div>
      </template>

      <div v-if="state === 'working'" class="flex items-center gap-2 text-sm text-muted">
        <UIcon name="i-lucide-loader-circle" class="size-4 animate-spin" />
        Confirming your address…
      </div>

      <div v-else-if="state === 'confirmed'" class="space-y-4">
        <UAlert
          color="success"
          variant="soft"
          icon="i-lucide-circle-check"
          title="Address confirmed"
          :description="
            username
              ? `The address is now confirmed for ${username}. Paprika can use it for account notifications.`
              : 'The address is now confirmed. Paprika can use it for account notifications.'
          "
        />
        <UButton to="/admin/profile" block icon="i-lucide-user">Go to your profile</UButton>
      </div>

      <div v-else class="space-y-4">
        <UAlert color="error" variant="soft" icon="i-lucide-circle-x" :title="error" />
        <p class="text-sm text-muted">
          Confirmation links expire after 30 minutes and can only be used once. Open your profile
          and send yourself a new one.
        </p>
        <UButton to="/admin/profile" block color="neutral" variant="soft" icon="i-lucide-user">
          Go to your profile
        </UButton>
      </div>
    </UCard>
  </div>
</template>
