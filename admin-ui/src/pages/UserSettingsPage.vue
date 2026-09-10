<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '@/lib/api'
import { useAppToast } from '@/composables/useAppToast'
import { useBootstrap } from '@/composables/useBootstrap'

const toast = useAppToast()
const { load, bootstrap } = useBootstrap()

const hasActiveTenant = computed(() => !!bootstrap.value?.hasActiveTenant)
const activeTenant = computed(() => bootstrap.value?.activeTenant ?? null)

const loading = ref(false)
const saving = ref(false)

const registrationEnabled = ref(false)
const passwordResetEnabled = ref(false)
const passwordResetUrl = ref('')
const emailVerificationEnabled = ref(false)
const emailVerificationUrl = ref('')
const emailVerificationRequired = ref(false)
const webhookAllowlist = ref('')

onMounted(async () => {
  await load()
  syncFromTenant()
})

watch(hasActiveTenant, () => {
  syncFromTenant()
})

watch(
  () => emailVerificationEnabled.value,
  (enabled) => {
    if (!enabled) {
      emailVerificationRequired.value = false
    }
  }
)

function syncFromTenant() {
  const tenant = activeTenant.value
  if (!tenant) {
    registrationEnabled.value = false
    passwordResetEnabled.value = false
    passwordResetUrl.value = ''
    emailVerificationEnabled.value = false
    emailVerificationUrl.value = ''
    emailVerificationRequired.value = false
    webhookAllowlist.value = ''
    return
  }
  registrationEnabled.value = tenant.registrationEnabled ?? false
  passwordResetEnabled.value = tenant.passwordResetEnabled ?? false
  passwordResetUrl.value = tenant.passwordResetUrl ?? ''
  emailVerificationEnabled.value = tenant.emailVerificationEnabled ?? false
  emailVerificationUrl.value = tenant.emailVerificationUrl ?? ''
  emailVerificationRequired.value = tenant.emailVerificationRequired ?? false
  webhookAllowlist.value = (tenant.webhookAllowlist ?? []).join(', ')
}

async function save() {
  const tenant = activeTenant.value
  if (!tenant) return

  saving.value = true
  try {
    await api.updateTenant(tenant.id, {
      registrationEnabled: registrationEnabled.value,
      passwordResetEnabled: passwordResetEnabled.value,
      passwordResetUrl: passwordResetEnabled.value ? passwordResetUrl.value || null : null,
      emailVerificationEnabled: emailVerificationEnabled.value,
      emailVerificationUrl: emailVerificationEnabled.value ? emailVerificationUrl.value || null : null,
      emailVerificationRequired: emailVerificationRequired.value,
      webhookAllowlist: webhookAllowlist.value
        .split(',')
        .map((host) => host.trim())
        .filter(Boolean)
    })
    await load(true)
    toast.add({
      title: 'User settings saved',
      color: 'success',
      icon: 'i-lucide-circle-check'
    })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to save user settings',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="space-y-4">
    <UCard
      v-if="!hasActiveTenant"
      variant="soft"
      icon="i-lucide-globe"
      title="Select a tenant"
      description="Choose a tenant in the sidebar to configure its user auth settings."
    />

    <template v-if="hasActiveTenant">
      <form class="space-y-4" @submit.prevent="save">
        <UCard :ui="{ body: 'p-4 sm:p-4' }">
          <div class="flex items-start justify-between gap-4">
            <div>
              <p class="font-medium">Self-registration</p>
              <p class="mt-1 text-sm text-muted">
                Allow new users to sign up via
                <code class="text-xs">POST /api/auth/register</code>
                with tenant
                <span class="font-medium text-default">{{ activeTenant?.slug }}</span>.
              </p>
            </div>
            <USwitch v-model="registrationEnabled" />
          </div>
        </UCard>

        <UCard :ui="{ body: 'p-4 sm:p-4' }">
          <div class="flex items-start justify-between gap-4">
            <div>
              <p class="font-medium">Password reset</p>
              <p class="mt-1 text-sm text-muted">
                Enable
                <code class="text-xs">POST /api/auth/password/forgot</code>
                and
                <code class="text-xs">/reset</code>. Paprika emails the reset link via the instance
                SMTP settings.
              </p>
            </div>
            <USwitch v-model="passwordResetEnabled" />
          </div>
          <UFormField
            v-if="passwordResetEnabled"
            label="Reset link URL"
            help="Your app's reset page. Paprika appends ?token=…, or substitutes a {token} placeholder."
            class="mt-3 w-full"
          >
            <UInput
              v-model="passwordResetUrl"
              icon="i-lucide-link"
              class="w-full"
              placeholder="https://app.example.com/reset"
            />
          </UFormField>
        </UCard>

        <UCard :ui="{ body: 'p-4 sm:p-4' }">
          <div class="flex items-start justify-between gap-4">
            <div>
              <p class="font-medium">Email verification</p>
              <p class="mt-1 text-sm text-muted">
                Enable
                <code class="text-xs">POST /api/auth/verify/request</code>
                and
                <code class="text-xs">/confirm</code>. Confirming sets
                <code class="text-xs">emailVerified</code>
                on the user; it only gates login if required below.
              </p>
            </div>
            <USwitch v-model="emailVerificationEnabled" />
          </div>
          <UFormField
            v-if="emailVerificationEnabled"
            label="Verification link URL"
            help="Your app's verification page. Paprika appends ?token=…, or substitutes a {token} placeholder."
            class="mt-3 w-full"
          >
            <UInput
              v-model="emailVerificationUrl"
              icon="i-lucide-link"
              class="w-full"
              placeholder="https://app.example.com/verify"
            />
          </UFormField>
          <div
            v-if="emailVerificationEnabled"
            class="mt-3 flex items-start justify-between gap-4 border-t border-default pt-3"
          >
            <div>
              <p class="text-sm font-medium">Require for login</p>
              <p class="mt-1 text-sm text-muted">
                Block login for users whose email isn't verified yet.
              </p>
            </div>
            <USwitch v-model="emailVerificationRequired" />
          </div>
        </UCard>

        <UCard :ui="{ body: 'p-4 sm:p-4' }">
          <p class="font-medium">Webhook allowlist</p>
          <p class="mt-1 text-sm text-muted">
            Loopback and private-network addresses are blocked as webhook targets by default. List
            <code class="text-xs">host:port</code> combinations this tenant's hooks may target,
            separated by commas.
          </p>
          <UFormField class="mt-3 w-full">
            <UInput
              v-model="webhookAllowlist"
              icon="i-lucide-shield-check"
              class="w-full font-mono"
              placeholder="127.0.0.1:8092, 192.168.1.10:9000"
            />
          </UFormField>
        </UCard>

        <div class="flex justify-end">
          <UButton type="submit" :loading="saving" icon="i-lucide-save">Save settings</UButton>
        </div>
      </form>
    </template>
  </div>
</template>