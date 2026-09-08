<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppLogo from '@/components/AppLogo.vue'
import { api } from '@/lib/api'
import { useBootstrap } from '@/composables/useBootstrap'

const router = useRouter()
const { load } = useBootstrap()

const token = ref('')
const username = ref('')
const password = ref('')
const confirmPassword = ref('')
const loading = ref(false)
const error = ref('')

onMounted(() => {
  token.value = new URLSearchParams(window.location.hash.slice(1)).get('token') || ''
  if (token.value) {
    window.history.replaceState(window.history.state, '', '/setup')
  } else {
    error.value = 'The setup token is missing. Open the setup link you were given.'
  }
})

async function submit() {
  error.value = ''
  if (!token.value) {
    error.value = 'The setup token is missing or no longer available.'
    return
  }
  if (!username.value.trim()) {
    error.value = 'Please choose a username'
    return
  }
  if (password.value.length < 16) {
    error.value = 'Password must be at least 16 characters long'
    return
  }
  if (password.value !== confirmPassword.value) {
    error.value = 'Passwords do not match'
    return
  }

  loading.value = true
  try {
    await api.completeSuperadminSetup(token.value, username.value.trim(), password.value)
    token.value = ''
    username.value = ''
    password.value = ''
    confirmPassword.value = ''
    await load(true)
    await router.replace('/')
  } catch (err) {
    error.value = err instanceof Error ? err.message : 'Failed to complete setup'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="flex min-h-svh flex-col items-center justify-center gap-6 bg-muted/30 p-4">
    <AppLogo size="lg" show-text subtitle="Secure account setup" />

    <UCard class="w-full max-w-md">
      <template #header>
        <div>
          <h1 class="text-lg font-semibold tracking-tight">Create superadmin account</h1>
          <p class="text-sm text-muted">
            Complete the setup using your one-time link. Choose your username and a password with
            at least 16 characters.
          </p>
        </div>
      </template>

      <UAlert
        v-if="error"
        color="error"
        variant="soft"
        icon="i-lucide-circle-x"
        :title="error"
        class="mb-4"
      />

      <form class="w-full space-y-4" @submit.prevent="submit">
        <UAlert
          color="primary"
          variant="soft"
          icon="i-lucide-shield-check"
          title="One-time setup"
          description="The setup token expires after 30 minutes and is invalidated immediately after use."
        />

        <UFormField label="Username" required class="w-full">
          <UInput
            v-model="username"
            class="w-full"
            type="text"
            autocomplete="username"
            icon="i-lucide-user"
            autofocus
          />
        </UFormField>

        <UFormField
          label="Password"
          required
          class="w-full"
          help="Must be at least 16 characters long"
        >
          <UInput
            v-model="password"
            class="w-full"
            type="password"
            autocomplete="new-password"
            minlength="16"
            icon="i-lucide-lock-keyhole"
          />
        </UFormField>

        <UFormField label="Confirm password" required class="w-full">
          <UInput
            v-model="confirmPassword"
            class="w-full"
            type="password"
            autocomplete="new-password"
            minlength="16"
            icon="i-lucide-lock-keyhole"
          />
        </UFormField>

        <UButton
          type="submit"
          block
          :loading="loading"
          :disabled="!token || !username.trim() || password.length < 16 || password !== confirmPassword"
          icon="i-lucide-circle-check"
        >
          Complete setup
        </UButton>
      </form>
    </UCard>
  </div>
</template>
