<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import QRCode from 'qrcode'
import { api } from '@/lib/api'
import { modalUi } from '@/lib/overlay-ui'
import { useAppToast } from '@/composables/useAppToast'
import { useBootstrap } from '@/composables/useBootstrap'
import type { SuperadminProfile, TwoFactorSetupResult } from '@/types'

/** What the picture is scaled to before it is sent; the server rejects anything much larger. */
const AVATAR_SIZE = 256

const toast = useAppToast()
const { load } = useBootstrap()

const loading = ref(true)
const profile = ref<SuperadminProfile | null>(null)

const email = ref('')
const savingEmail = ref(false)
const resendingEmail = ref(false)
const removingEmail = ref(false)
const savingLoginAlert = ref(false)
const uploadingAvatar = ref(false)
const avatarInput = ref<HTMLInputElement | null>(null)

const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const savingPassword = ref(false)

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

const twoFactorEnabled = computed(() => profile.value?.twoFactorEnabled === true)
const emailVerified = computed(() => profile.value?.emailVerified === true)
const smtpConfigured = computed(() => profile.value?.smtpConfigured === true)
const hasEmail = computed(() => !!profile.value?.email)
const emailChanged = computed(() => email.value.trim().toLowerCase() !== (profile.value?.email || ''))

/** The alert needs somewhere to send to and something to send with, and says which one is missing. */
const loginAlertBlockedReason = computed(() => {
  if (!smtpConfigured.value) {
    return 'Configure SMTP on this instance to use sign-in alerts.'
  }
  if (!emailVerified.value) {
    return 'Confirm your email address to use sign-in alerts.'
  }
  return ''
})

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
  await refreshProfile()
})

function apply(next: SuperadminProfile) {
  profile.value = next
  email.value = next.email || ''
}

function fail(error: unknown, fallback: string) {
  toast.add({
    title: error instanceof Error ? error.message : fallback,
    color: 'error',
    icon: 'i-lucide-circle-x'
  })
}

async function refreshProfile() {
  loading.value = true
  try {
    apply(await api.getProfile())
  } catch (error) {
    fail(error, 'Failed to load your profile')
  } finally {
    loading.value = false
  }
}

async function saveEmail() {
  savingEmail.value = true
  try {
    const next = await api.updateProfileEmail(email.value.trim())
    apply(next)
    // Reloading the whole bootstrap payload is deliberate: nothing else keeps the header in sync
    // with an account that just changed.
    await load(true)
    notifyVerificationSent(next, 'Email address saved')
  } catch (error) {
    fail(error, 'Failed to save the email address')
  } finally {
    savingEmail.value = false
  }
}

async function resendVerification() {
  resendingEmail.value = true
  try {
    const next = await api.resendProfileEmailVerification()
    apply(next)
    notifyVerificationSent(next, 'Confirmation link sent')
  } catch (error) {
    fail(error, 'Failed to send the confirmation link')
  } finally {
    resendingEmail.value = false
  }
}

function notifyVerificationSent(next: SuperadminProfile, title: string) {
  if (next.emailVerified) {
    toast.add({ title, color: 'success', icon: 'i-lucide-circle-check' })
    return
  }

  if (next.verificationEmailSent) {
    toast.add({
      title,
      description: `Open the link we sent to ${next.email} to confirm the address. It expires in 30 minutes.`,
      color: 'success',
      icon: 'i-lucide-mail'
    })
    return
  }

  toast.add({
    title,
    description: 'SMTP is not configured, so no confirmation link could be sent.',
    color: 'warning',
    icon: 'i-lucide-triangle-alert'
  })
}

async function removeEmail() {
  removingEmail.value = true
  try {
    apply(await api.deleteProfileEmail())
    await load(true)
    toast.add({ title: 'Email address removed', color: 'success', icon: 'i-lucide-circle-check' })
  } catch (error) {
    fail(error, 'Failed to remove the email address')
  } finally {
    removingEmail.value = false
  }
}

async function toggleLoginAlert(enabled: boolean) {
  savingLoginAlert.value = true
  try {
    apply(await api.setLoginAlert(enabled))
    toast.add({
      title: enabled ? 'Sign-in alerts enabled' : 'Sign-in alerts disabled',
      color: 'success',
      icon: enabled ? 'i-lucide-bell' : 'i-lucide-bell-off'
    })
  } catch (error) {
    // The switch already moved, so it has to be put back where the server says it is.
    await refreshProfile()
    fail(error, 'Failed to update sign-in alerts')
  } finally {
    savingLoginAlert.value = false
  }
}

function pickAvatar() {
  avatarInput.value?.click()
}

async function onAvatarSelected(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) {
    return
  }

  uploadingAvatar.value = true
  try {
    apply(await api.updateAvatar(await toSquareDataUrl(file)))
    await load(true)
    toast.add({ title: 'Profile picture updated', color: 'success', icon: 'i-lucide-circle-check' })
  } catch (error) {
    fail(error, 'Failed to update the profile picture')
  } finally {
    uploadingAvatar.value = false
  }
}

async function removeAvatar() {
  uploadingAvatar.value = true
  try {
    apply(await api.deleteAvatar())
    await load(true)
    toast.add({ title: 'Profile picture removed', color: 'success', icon: 'i-lucide-circle-check' })
  } catch (error) {
    fail(error, 'Failed to remove the profile picture')
  } finally {
    uploadingAvatar.value = false
  }
}

/**
 * Scales and centre-crops the picked file to a square PNG in the browser. Doing it here keeps the
 * upload small enough to live on the account record, and it is also what makes the picture safe to
 * store: the canvas only ever produces image data, so nothing of the original file survives.
 *
 * The file is decoded with createImageBitmap instead of being handed to an <img> as an object
 * URL. That URL has the blob: scheme, which the img-src directive of the Content Security Policy
 * does not cover - 'self' never matches blob:, so the browser refused to load it and every
 * upload failed as "not an image", whatever the file actually was. Decoding the file directly
 * needs no URL at all, so the policy can stay as strict as it is.
 */
async function toSquareDataUrl(file: File): Promise<string> {
  let bitmap: ImageBitmap
  try {
    // Phone photos are usually stored sideways with an EXIF tag saying which way is up. An <img>
    // applies that tag on its own; a bitmap has to be asked for it.
    bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' })
  } catch {
    throw new Error('This file is not an image Paprika can read')
  }

  try {
    const canvas = document.createElement('canvas')
    canvas.width = AVATAR_SIZE
    canvas.height = AVATAR_SIZE
    const context = canvas.getContext('2d')
    if (!context) {
      throw new Error('This browser cannot process the image')
    }

    const side = Math.min(bitmap.width, bitmap.height)
    context.drawImage(
      bitmap,
      (bitmap.width - side) / 2,
      (bitmap.height - side) / 2,
      side,
      side,
      0,
      0,
      AVATAR_SIZE,
      AVATAR_SIZE
    )

    return canvas.toDataURL('image/png')
  } finally {
    bitmap.close()
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
    toast.add({ title: 'Passwords do not match', color: 'error', icon: 'i-lucide-circle-x' })
    return
  }

  savingPassword.value = true
  try {
    await api.changeSuperadminPassword(currentPassword.value, newPassword.value)
    currentPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
    toast.add({ title: 'Password changed', color: 'success', icon: 'i-lucide-circle-check' })
  } catch (error) {
    fail(error, 'Failed to change password')
  } finally {
    savingPassword.value = false
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
    fail(error, 'Failed to start 2FA setup')
  } finally {
    setupLoading.value = false
  }
}

async function confirmSetup() {
  setupLoading.value = true
  try {
    const result = await api.confirmTwoFactor(setupCode.value)
    setupFallbackCode.value = result.fallbackCode
    await refreshProfile()
    toast.add({
      title: 'Two-factor authentication enabled',
      color: 'success',
      icon: 'i-lucide-shield-check'
    })
  } catch (error) {
    fail(error, 'Invalid verification code')
  } finally {
    setupLoading.value = false
  }
}

async function confirmDisable() {
  disableLoading.value = true
  try {
    await api.disableTwoFactor(disablePassword.value, disableCode.value)
    disableOpen.value = false
    await refreshProfile()
    toast.add({
      title: 'Two-factor authentication disabled',
      color: 'success',
      icon: 'i-lucide-shield-off'
    })
  } catch (error) {
    fail(error, 'Failed to disable 2FA')
  } finally {
    disableLoading.value = false
  }
}
</script>

<template>
  <div class="space-y-4">
    <UCard>
      <template #header>
        <div class="flex items-center gap-2">
          <UIcon name="i-lucide-user" class="size-5 text-primary" />
          <h2 class="font-semibold">Account</h2>
        </div>
      </template>

      <div v-if="loading" class="text-sm text-muted">Loading your profile…</div>

      <div v-else class="flex flex-col gap-6 sm:flex-row sm:items-start">
        <div class="flex flex-col items-center gap-3">
          <UAvatar
            :src="profile?.avatarUrl || undefined"
            :alt="profile?.username"
            icon="i-lucide-user"
            size="3xl"
            class="size-24 ring-1 ring-default"
          />

          <div class="flex gap-2">
            <UButton
              size="sm"
              color="neutral"
              variant="soft"
              icon="i-lucide-upload"
              :loading="uploadingAvatar"
              @click="pickAvatar"
            >
              {{ profile?.avatarUrl ? 'Replace' : 'Upload' }}
            </UButton>
            <UButton
              v-if="profile?.avatarUrl"
              size="sm"
              color="error"
              variant="soft"
              icon="i-lucide-trash-2"
              :loading="uploadingAvatar"
              @click="removeAvatar"
            />
          </div>

          <input
            ref="avatarInput"
            type="file"
            accept="image/png,image/jpeg,image/webp"
            class="hidden"
            @change="onAvatarSelected"
          />
        </div>

        <div class="flex-1 space-y-4">
          <UFormField
            label="Username"
            class="w-full max-w-md"
            help="The name you sign in with. It is fixed for the lifetime of the account."
          >
            <UInput
              :model-value="profile?.username || ''"
              readonly
              disabled
              icon="i-lucide-user"
              class="w-full"
            />
          </UFormField>

          <p class="max-w-2xl text-sm text-muted">
            The picture is scaled to {{ AVATAR_SIZE }}×{{ AVATAR_SIZE }} pixels in your browser and
            shown next to your name in the header. PNG, JPEG and WebP are accepted.
          </p>
        </div>
      </div>
    </UCard>

    <UCard>
      <template #header>
        <div class="flex items-center gap-2">
          <UIcon name="i-lucide-mail" class="size-5 text-primary" />
          <h2 class="font-semibold">Email address</h2>
        </div>
      </template>

      <div v-if="loading" class="text-sm text-muted">Loading your profile…</div>

      <div v-else class="space-y-4">
        <div class="space-y-1">
          <div class="flex items-center gap-2">
            <h3 class="font-medium">Your address</h3>
            <UBadge v-if="!hasEmail" color="neutral" variant="soft" size="sm">Not set</UBadge>
            <UBadge v-else-if="emailVerified" color="success" variant="soft" size="sm">Confirmed</UBadge>
            <UBadge v-else color="warning" variant="soft" size="sm">Unconfirmed</UBadge>
          </div>
          <p class="max-w-2xl text-sm text-muted">
            Paprika only uses this address for notifications about your own account. It has to be
            confirmed once before anything is sent to it, so a typo can never quietly redirect your
            account mail somewhere else.
          </p>
        </div>

        <UAlert
          v-if="!smtpConfigured"
          color="warning"
          variant="soft"
          icon="i-lucide-triangle-alert"
          title="SMTP is not configured"
          description="This instance still has the default SMTP settings, so Paprika cannot send the confirmation link. Set smtp.host and the related keys first."
        />

        <form class="flex max-w-xl flex-col gap-4 sm:flex-row sm:items-end" @submit.prevent="saveEmail">
          <UFormField label="Email" class="w-full flex-1">
            <UInput
              v-model="email"
              type="email"
              placeholder="name@example.com"
              icon="i-lucide-mail"
              class="w-full"
            />
          </UFormField>
          <UButton
            type="submit"
            icon="i-lucide-save"
            :loading="savingEmail"
            :disabled="!email.trim() || !emailChanged"
          >
            Save
          </UButton>
        </form>

        <div v-if="hasEmail" class="flex flex-wrap gap-2">
          <UButton
            v-if="!emailVerified"
            color="neutral"
            variant="soft"
            icon="i-lucide-send"
            :loading="resendingEmail"
            :disabled="!smtpConfigured || emailChanged"
            @click="resendVerification"
          >
            Send confirmation link
          </UButton>
          <UButton
            color="error"
            variant="soft"
            icon="i-lucide-trash-2"
            :loading="removingEmail"
            @click="removeEmail"
          >
            Remove address
          </UButton>
        </div>
      </div>
    </UCard>

    <UCard>
      <template #header>
        <div class="flex items-center gap-2">
          <UIcon name="i-lucide-bell" class="size-5 text-primary" />
          <h2 class="font-semibold">Sign-in alerts</h2>
        </div>
      </template>

      <div v-if="loading" class="text-sm text-muted">Loading your profile…</div>

      <div v-else class="space-y-4">
        <div class="space-y-1">
          <h3 class="font-medium">Email me about new sign-ins</h3>
          <p class="max-w-2xl text-sm text-muted">
            Paprika recognises the devices your account is used from by a hash of the browser's user
            agent and the IP address the request came from. A sign-in from a combination it has not
            seen before sends you an email; the devices you already use stay quiet. Neither the user
            agent nor the address is stored — only that hash, and only for the last 20 devices.
          </p>
        </div>

        <UAlert
          v-if="loginAlertBlockedReason"
          color="neutral"
          variant="soft"
          icon="i-lucide-info"
          :description="loginAlertBlockedReason"
        />

        <USwitch
          :model-value="profile?.loginAlertEnabled === true"
          :disabled="savingLoginAlert || !!loginAlertBlockedReason"
          label="Send an email on sign-in from a new device"
          @update:model-value="toggleLoginAlert"
        />
      </div>
    </UCard>

    <UCard>
      <template #header>
        <div class="flex items-center gap-2">
          <UIcon name="i-lucide-shield" class="size-5 text-primary" />
          <h2 class="font-semibold">Security</h2>
        </div>
      </template>

      <div v-if="loading" class="text-sm text-muted">Loading your profile…</div>

      <div v-else class="space-y-4">
        <div class="space-y-1">
          <h3 class="font-medium">Password</h3>
          <p class="max-w-2xl text-sm text-muted">
            Change the password you sign in to the admin control plane with. The new password must
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
            />
          </UFormField>
          <UButton
            type="submit"
            class="w-fit"
            :loading="savingPassword"
            :disabled="
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
            <h3 class="font-medium">Two-factor authentication</h3>
            <UBadge :color="twoFactorEnabled ? 'success' : 'neutral'" variant="soft" size="xs">
              {{ twoFactorEnabled ? 'Enabled' : 'Disabled' }}
            </UBadge>
          </div>
          <p class="max-w-2xl text-sm text-muted">
            Require a TOTP code from an authenticator app when signing in. This applies to your
            account only — every superadmin decides for their own login.
          </p>
        </div>

        <UButton v-if="!twoFactorEnabled" icon="i-lucide-shield-plus" @click="openSetup">
          Enable 2FA
        </UButton>
        <UButton v-else color="error" variant="soft" icon="i-lucide-shield-off" @click="openDisable">
          Disable 2FA
        </UButton>
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
            <UButton v-if="setupStep === 'fallback'" icon="i-lucide-check" @click="finishSetup">
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
