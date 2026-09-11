<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppLogo from '@/components/AppLogo.vue'
import { api } from '@/lib/api'
import { useBootstrap } from '@/composables/useBootstrap'

const route = useRoute()
const router = useRouter()
const { load } = useBootstrap()

const step = ref<'credentials' | '2fa'>(route.query['2fa'] === '1' ? '2fa' : 'credentials')
const username = ref('')
const password = ref('')
const totpCode = ref('')
const loading = ref(false)
const error = ref('')

async function submitCredentials() {
  loading.value = true
  error.value = ''

  try {
    const result = await api.login(username.value, password.value)
    if (result.requiresTwoFactor) {
      step.value = '2fa'
      totpCode.value = ''
      return
    }
    await completeLogin()
  } catch (err) {
    error.value = err instanceof Error ? err.message : 'Sign in failed'
  } finally {
    loading.value = false
  }
}

async function submitTwoFactor() {
  loading.value = true
  error.value = ''

  try {
    await api.loginTwoFactor(totpCode.value)
    await completeLogin()
  } catch (err) {
    error.value = err instanceof Error ? err.message : 'Invalid verification code'
  } finally {
    loading.value = false
  }
}

async function completeLogin() {
  await load(true)
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
  await router.replace(redirect)
}

function backToCredentials() {
  step.value = 'credentials'
  totpCode.value = ''
  error.value = ''
}

</script>

<template>
  <div class="flex min-h-svh flex-col items-center justify-center gap-6 bg-muted/30 p-4">
    <AppLogo size="lg" show-text :subtitle="step === '2fa' ? 'Secure sign-in' : 'Admin Control Plane'" />

    <UCard class="w-full max-w-md">
      <template #header>
        <div>
          <h1 class="text-lg font-semibold tracking-tight">
            {{ step === '2fa' ? 'Two-factor authentication' : 'Sign in' }}
          </h1>
          <p class="text-sm text-muted">
            {{ step === '2fa' ? 'Enter your authenticator code' : 'Sign in to the admin panel' }}
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

      <form v-if="step === 'credentials'" class="w-full space-y-4" @submit.prevent="submitCredentials">
        <UFormField label="Username" required class="w-full">
          <UInput
            v-model="username"
            class="w-full"
            autocomplete="username"
            autocapitalize="none"
            spellcheck="false"
            icon="i-lucide-user"
            autofocus
          />
        </UFormField>

        <UFormField label="Password" required class="w-full">
          <UInput
            v-model="password"
            class="w-full"
            type="password"
            autocomplete="current-password"
            icon="i-lucide-lock"
          />
        </UFormField>

        <p class="text-sm text-muted">
          Superadmin access only. After sign-in, choose a tenant from the control plane.
        </p>

        <UButton type="submit" block :loading="loading" icon="i-lucide-log-in">
          Sign in
        </UButton>
      </form>

      <form v-else class="w-full space-y-4" @submit.prevent="submitTwoFactor">
        <UAlert
          color="primary"
          variant="soft"
          icon="i-lucide-shield-check"
          title="Two-factor authentication"
          description="Open your authenticator app and enter the current 6-digit code, or use your fallback code if you no longer have access to it."
        />

        <UFormField label="Verification or fallback code" required class="w-full">
          <UInput
            v-model="totpCode"
            class="w-full"
            autocomplete="one-time-code"
            maxlength="32"
            icon="i-lucide-key-round"
            autofocus
          />
        </UFormField>

        <div class="flex gap-2">
          <UButton variant="ghost" color="neutral" icon="i-lucide-arrow-left" @click="backToCredentials">
            Back
          </UButton>
          <UButton
            type="submit"
            class="flex-1"
            :loading="loading"
            :disabled="totpCode.trim().length < 6"
            icon="i-lucide-shield-check"
          >
            Verify and sign in
          </UButton>
        </div>
      </form>
    </UCard>
  </div>
</template>
