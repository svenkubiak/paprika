<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import QRCode from 'qrcode'
import { api } from '@/lib/api'
import { modalUi, selectContentProps, selectMenuUi } from '@/lib/overlay-ui'
import { useAppToast } from '@/composables/useAppToast'
import { useBootstrap } from '@/composables/useBootstrap'
import { SELECT_EMPTY } from '@/lib/utils'
import type { TwoFactorSetupResult } from '@/types'

const toast = useAppToast()
const { load, bootstrap } = useBootstrap()

const loading = ref(true)
const savingLogs = ref(false)
const savingDefaultTenant = ref(false)
const savingPassword = ref(false)
const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const twoFactorEnabled = ref(false)
const requestLogRetentionDays = ref(7)
const defaultTenantId = ref(SELECT_EMPTY)

const tenantItems = computed(() =>
  (bootstrap.value?.tenants || []).map((tenant) => ({
    label: `${tenant.name} (${tenant.slug})`,
    value: tenant.id
  }))
)

const setupOpen = ref(false)
const setupPassword = ref('')
const setupLoading = ref(false)
const setupData = ref<TwoFactorSetupResult | null>(null)
const setupCode = ref('')
const setupQrCode = ref('')
const setupFallbackCode = ref('')

const disableOpen = ref(false)
const disablePassword = ref('')
const disableCode = ref('')
const disableLoading = ref(false)

const setupStep = computed(() => {
  if (setupFallbackCode.value) {
    return 'fallback'
  }
  return setupData.value ? 'verify' : 'password'
})

watch(setupData, async (value) => {
  if (value?.uri) {
    setupQrCode.value = await QRCode.toDataURL(value.uri, { margin: 1, width: 176 })
  } else {
    setupQrCode.value = ''
  }
})

onMounted(async () => {
  await load()
  await refreshSettings()
})

async function refreshSettings() {
  loading.value = true
  try {
    const settings = await api.getSettings()
    twoFactorEnabled.value = !!settings.twoFactorEnabled
    requestLogRetentionDays.value =
      typeof settings.requestLogRetentionDays === 'number'
        ? settings.requestLogRetentionDays
        : 7
    defaultTenantId.value = settings.defaultTenantId || SELECT_EMPTY
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to load settings',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    loading.value = false
  }
}

function openSetup() {
  setupPassword.value = ''
  setupCode.value = ''
  setupData.value = null
  setupFallbackCode.value = ''
  setupOpen.value = true
}

function finishSetup() {
  setupFallbackCode.value = ''
  setupOpen.value = false
}

function openDisable() {
  disablePassword.value = ''
  disableCode.value = ''
  disableOpen.value = true
}

async function beginSetup() {
  setupLoading.value = true
  try {
    setupData.value = await api.setupTwoFactor(setupPassword.value)
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to start 2FA setup',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    setupLoading.value = false
  }
}

async function confirmSetup() {
  setupLoading.value = true
  try {
    const result = await api.confirmTwoFactor(setupCode.value)
    twoFactorEnabled.value = true
    setupFallbackCode.value = result.fallbackCode
    toast.add({
      title: 'Two-factor authentication enabled',
      color: 'success',
      icon: 'i-lucide-shield-check'
    })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Invalid verification code',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    setupLoading.value = false
  }
}

async function confirmDisable() {
  disableLoading.value = true
  try {
    await api.disableTwoFactor(disablePassword.value, disableCode.value)
    twoFactorEnabled.value = false
    disableOpen.value = false
    toast.add({
      title: 'Two-factor authentication disabled',
      color: 'success',
      icon: 'i-lucide-shield-off'
    })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to disable 2FA',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    disableLoading.value = false
  }
}

async function saveRequestLogRetention() {
  savingLogs.value = true
  try {
    const settings = await api.updateSettings({
      requestLogRetentionDays: requestLogRetentionDays.value
    })
    requestLogRetentionDays.value = settings.requestLogRetentionDays
    toast.add({
      title: 'Request log retention updated',
      color: 'success',
      icon: 'i-lucide-circle-check'
    })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to update log retention',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    savingLogs.value = false
  }
}

async function saveDefaultTenant() {
  savingDefaultTenant.value = true
  try {
    const settings = await api.updateSettings({
      defaultTenantId: defaultTenantId.value === SELECT_EMPTY ? '' : defaultTenantId.value
    })
    defaultTenantId.value = settings.defaultTenantId || SELECT_EMPTY
    toast.add({
      title: 'Default tenant updated',
      color: 'success',
      icon: 'i-lucide-circle-check'
    })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to update default tenant',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    savingDefaultTenant.value = false
  }
}

async function savePassword() {
  if (newPassword.value.length < 16) {
    toast.add({
      title: 'Password must be at least 16 characters long',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    toast.add({
      title: 'Passwords do not match',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
    return
  }

  savingPassword.value = true
  try {
    await api.changeSuperadminPassword(currentPassword.value, newPassword.value)
    currentPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
    toast.add({
      title: 'Superadmin password changed',
      color: 'success',
      icon: 'i-lucide-circle-check'
    })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to change password',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    savingPassword.value = false
  }
}
</script>

<template>
  <div class="space-y-4">
    <UCard>
      <template #header>
        <div class="flex items-center gap-2">
          <UIcon name="i-lucide-shield" class="size-5 text-primary" />
          <h2 class="font-semibold">Security</h2>
        </div>
      </template>

      <div v-if="loading" class="text-sm text-muted">Loading settings…</div>

      <div v-else class="space-y-4">
        <div class="space-y-1">
          <h3 class="font-medium">Superadmin password</h3>
          <p class="max-w-2xl text-sm text-muted">
            Change the password used to sign in to the admin control plane. The new password must
            contain at least 16 characters.
          </p>
        </div>

        <form class="grid max-w-xl gap-4" @submit.prevent="savePassword">
          <UFormField label="Current password" required class="w-full">
            <UInput
              v-model="currentPassword"
              type="password"
              autocomplete="current-password"
              icon="i-lucide-lock"
              class="w-full"
              :disabled="!bootstrap?.isSuperAdmin"
            />
          </UFormField>
          <UFormField label="New password" required class="w-full">
            <UInput
              v-model="newPassword"
              type="password"
              autocomplete="new-password"
              minlength="16"
              icon="i-lucide-lock-keyhole"
              class="w-full"
              :disabled="!bootstrap?.isSuperAdmin"
            />
          </UFormField>
          <UFormField label="Confirm new password" required class="w-full">
            <UInput
              v-model="confirmPassword"
              type="password"
              autocomplete="new-password"
              minlength="16"
              icon="i-lucide-lock-keyhole"
              class="w-full"
              :disabled="!bootstrap?.isSuperAdmin"
            />
          </UFormField>
          <UButton
            type="submit"
            class="w-fit"
            :loading="savingPassword"
            :disabled="
              !bootstrap?.isSuperAdmin ||
              !currentPassword ||
              newPassword.length < 16 ||
              newPassword !== confirmPassword ||
              newPassword === currentPassword
            "
            icon="i-lucide-save"
          >
            Change password
          </UButton>
        </form>

        <USeparator />

        <div class="space-y-1">
          <div class="flex items-center gap-2">
            <h3 class="font-medium">Superadmin two-factor authentication</h3>
            <UBadge
              :color="twoFactorEnabled ? 'success' : 'neutral'"
              variant="soft"
              size="xs"
            >
              {{ twoFactorEnabled ? 'Enabled' : 'Disabled' }}
            </UBadge>
          </div>
          <p class="max-w-2xl text-sm text-muted">
            Require a TOTP code from an authenticator app when signing in as superadmin.
            Applies to {{ bootstrap?.isSuperAdmin ? 'your account' : 'the superadmin account' }}.
          </p>
        </div>

        <UButton
          v-if="!twoFactorEnabled"
          icon="i-lucide-shield-plus"
          :disabled="!bootstrap?.isSuperAdmin"
          @click="openSetup"
        >
          Enable 2FA
        </UButton>
        <UButton
          v-else
          color="error"
          variant="soft"
          icon="i-lucide-shield-off"
          :disabled="!bootstrap?.isSuperAdmin"
          @click="openDisable"
        >
          Disable 2FA
        </UButton>
      </div>
    </UCard>

    <UCard>
      <template #header>
        <div class="flex items-center gap-2">
          <UIcon name="i-lucide-building-2" class="size-5 text-primary" />
          <h2 class="font-semibold">Tenants</h2>
        </div>
      </template>

      <div v-if="loading" class="text-sm text-muted">Loading settings…</div>

      <div v-else class="space-y-4">
        <div class="space-y-1">
          <h3 class="font-medium">Default tenant</h3>
          <p class="max-w-2xl text-sm text-muted">
            Automatically selected for superadmin after sign-in when no tenant is active.
            If unset, Paprika falls back to the bootstrap tenant from config.
          </p>
        </div>

        <form class="flex max-w-xl flex-col gap-4 sm:flex-row sm:items-end" @submit.prevent="saveDefaultTenant">
          <UFormField label="Tenant" class="w-full flex-1">
            <USelect
              v-model="defaultTenantId"
              :items="[{ label: 'Use config fallback', value: SELECT_EMPTY }, ...tenantItems]"
              placeholder="Select default tenant"
              icon="i-lucide-building-2"
              :content="selectContentProps"
              :ui="selectMenuUi"
              class="w-full"
              :disabled="!bootstrap?.isSuperAdmin"
            />
          </UFormField>
          <UButton
            type="submit"
            :loading="savingDefaultTenant"
            icon="i-lucide-save"
            :disabled="!bootstrap?.isSuperAdmin"
          >
            Save
          </UButton>
        </form>
      </div>
    </UCard>

    <UCard>
      <template #header>
        <div class="flex items-center gap-2">
          <UIcon name="i-lucide-scroll-text" class="size-5 text-primary" />
          <h2 class="font-semibold">Request logs</h2>
        </div>
      </template>

      <div v-if="loading" class="text-sm text-muted">Loading settings…</div>

      <div v-else class="space-y-4">
        <div class="space-y-1">
          <h3 class="font-medium">Automatic cleanup</h3>
          <p class="max-w-2xl text-sm text-muted">
            Request log entries older than this number of days are deleted automatically.
            Set to 0 to keep all logs indefinitely.
          </p>
        </div>

        <form class="flex max-w-md flex-col gap-4 sm:flex-row sm:items-end" @submit.prevent="saveRequestLogRetention">
          <UFormField label="Retention (days)" required class="w-full flex-1">
            <UInput
              v-model.number="requestLogRetentionDays"
              type="number"
              min="0"
              max="3650"
              icon="i-lucide-calendar-clock"
              class="w-full"
              :disabled="!bootstrap?.isSuperAdmin"
            />
          </UFormField>
          <UButton
            type="submit"
            :loading="savingLogs"
            icon="i-lucide-save"
            :disabled="!bootstrap?.isSuperAdmin"
          >
            Save
          </UButton>
        </form>
      </div>
    </UCard>
  </div>

  <UModal v-model:open="setupOpen" :dismissible="setupStep !== 'fallback'" :ui="modalUi">
    <template #content>
      <UCard>
        <template #header>
          <h3 class="font-semibold">
            {{
              setupStep === 'password'
                ? 'Confirm password'
                : setupStep === 'verify'
                  ? 'Set up authenticator'
                  : 'Save your fallback code'
            }}
          </h3>
        </template>

        <div v-if="setupStep === 'fallback'" class="space-y-4">
          <UAlert
            color="warning"
            variant="soft"
            icon="i-lucide-triangle-alert"
            title="This code is shown only once"
            description="Store it somewhere safe. It lets you sign in if you lose access to your authenticator app, and can be used only a single time."
          />

          <UFormField label="Fallback code" class="w-full">
            <UInput
              :model-value="setupFallbackCode"
              readonly
              class="w-full font-mono text-xs"
              icon="i-lucide-key-round"
            />
          </UFormField>
        </div>

        <div v-else-if="setupStep === 'password'" class="space-y-4">
          <p class="text-sm text-muted">
            Enter your current password to begin two-factor authentication setup.
          </p>
          <UFormField label="Password" required class="w-full">
            <UInput
              v-model="setupPassword"
              class="w-full"
              type="password"
              autocomplete="current-password"
              icon="i-lucide-lock"
            />
          </UFormField>
        </div>

        <div v-else class="space-y-4">
          <p class="text-sm text-muted">
            Scan the QR code with your authenticator app, then enter the 6-digit code to confirm.
          </p>

          <div v-if="setupQrCode" class="flex justify-center">
            <img
              :src="setupQrCode"
              alt="2FA QR code"
              class="size-44 rounded-lg border border-default bg-white p-2"
            />
          </div>

          <UFormField label="Manual entry key" class="w-full">
            <UInput :model-value="setupData?.secret || ''" readonly class="w-full font-mono text-xs" />
          </UFormField>

          <UFormField label="Verification code" required class="w-full">
            <UInput
              v-model="setupCode"
              class="w-full"
              inputmode="numeric"
              autocomplete="one-time-code"
              maxlength="6"
              icon="i-lucide-key-round"
            />
          </UFormField>
        </div>

        <template #footer>
          <div class="flex justify-end gap-2">
            <UButton
              v-if="setupStep === 'fallback'"
              icon="i-lucide-check"
              @click="finishSetup"
            >
              I've saved this code
            </UButton>
            <template v-else>
              <UButton variant="ghost" color="neutral" @click="setupOpen = false">Cancel</UButton>
              <UButton
                v-if="setupStep === 'password'"
                :loading="setupLoading"
                :disabled="!setupPassword"
                @click="beginSetup"
              >
                Continue
              </UButton>
              <UButton
                v-else
                :loading="setupLoading"
                :disabled="setupCode.trim().length < 6"
                @click="confirmSetup"
              >
                Enable 2FA
              </UButton>
            </template>
          </div>
        </template>
      </UCard>
    </template>
  </UModal>

  <UModal v-model:open="disableOpen" :ui="modalUi">
    <template #content>
      <UCard>
        <template #header>
          <h3 class="font-semibold">Disable two-factor authentication</h3>
        </template>

        <div class="space-y-4">
          <UAlert
            color="warning"
            variant="soft"
            icon="i-lucide-triangle-alert"
            title="This reduces account security"
            description="You will need only your password to sign in after disabling 2FA."
          />

          <UFormField label="Password" required class="w-full">
            <UInput
              v-model="disablePassword"
              class="w-full"
              type="password"
              autocomplete="current-password"
              icon="i-lucide-lock"
            />
          </UFormField>

          <UFormField label="Authenticator code" required class="w-full">
            <UInput
              v-model="disableCode"
              class="w-full"
              inputmode="numeric"
              autocomplete="one-time-code"
              maxlength="6"
              icon="i-lucide-key-round"
            />
          </UFormField>
        </div>

        <template #footer>
          <div class="flex justify-end gap-2">
            <UButton variant="ghost" color="neutral" @click="disableOpen = false">Cancel</UButton>
            <UButton
              color="error"
              :loading="disableLoading"
              :disabled="!disablePassword || disableCode.trim().length < 6"
              @click="confirmDisable"
            >
              Disable 2FA
            </UButton>
          </div>
        </template>
      </UCard>
    </template>
  </UModal>
</template>
