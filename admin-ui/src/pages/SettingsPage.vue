<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import PField from '@/components/PField.vue'
import { api } from '@/lib/api'
import { selectContentProps, selectMenuUi } from '@/lib/overlay-ui'
import { useAppToast } from '@/composables/useAppToast'
import { useBootstrap } from '@/composables/useBootstrap'
import { SELECT_EMPTY } from '@/lib/utils'
import type { AppLicense } from '@/types'

const toast = useAppToast()
const { load, bootstrap } = useBootstrap()

const loading = ref(true)
const savingLogs = ref(false)
const savingDefaultTenant = ref(false)
const requestLogRetentionDays = ref(7)
const requestLogClientInfo = ref(false)
const requestLogClientIp = ref<'off' | 'truncated' | 'full'>('off')
const savingClientInfo = ref(false)
/** Off by default: admin UI traffic is Paprika's own, not the tenant's API traffic. */
const requestLogAdminUi = ref(false)
const savingAdminUi = ref(false)
const license = ref<AppLicense>({ status: 'none' })
const licenseKey = ref('')
const savingLicense = ref(false)
const removingLicense = ref(false)

const clientIpOptions = [
  { label: 'Do not log (default)', value: 'off' },
  { label: 'Truncated (IPv4 /24, IPv6 /48)', value: 'truncated' },
  { label: 'Full address', value: 'full' }
]
const defaultTenantId = ref(SELECT_EMPTY)

const tenantItems = computed(() =>
  (bootstrap.value?.tenants || []).map((tenant) => ({
    label: `${tenant.name} (${tenant.slug})`,
    value: tenant.id
  }))
)

onMounted(async () => {
  await load()
  await refreshSettings()
})

async function refreshSettings() {
  loading.value = true
  try {
    const settings = await api.getSettings()
    requestLogRetentionDays.value =
      typeof settings.requestLogRetentionDays === 'number'
        ? settings.requestLogRetentionDays
        : 7
    defaultTenantId.value = settings.defaultTenantId || SELECT_EMPTY
    requestLogClientInfo.value = !!settings.requestLogClientInfo
    requestLogClientIp.value = settings.requestLogClientIp || 'off'
    requestLogAdminUi.value = !!settings.requestLogAdminUi
    license.value = settings.license || { status: 'none' }
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

async function saveClientInfo() {
  savingClientInfo.value = true
  try {
    const settings = await api.updateSettings({
      requestLogClientInfo: requestLogClientInfo.value,
      requestLogClientIp: requestLogClientIp.value
    })
    requestLogClientInfo.value = !!settings.requestLogClientInfo
    requestLogClientIp.value = settings.requestLogClientIp || 'off'
    toast.add({
      title: 'Client information settings updated',
      color: 'success',
      icon: 'i-lucide-circle-check'
    })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to update client information settings',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    savingClientInfo.value = false
  }
}

async function saveAdminUiLogging() {
  savingAdminUi.value = true
  try {
    const settings = await api.updateSettings({ requestLogAdminUi: requestLogAdminUi.value })
    requestLogAdminUi.value = !!settings.requestLogAdminUi
    toast.add({
      title: 'Admin UI logging updated',
      color: 'success',
      icon: 'i-lucide-circle-check'
    })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to update admin UI logging',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    savingAdminUi.value = false
  }
}

async function saveLicense() {
  savingLicense.value = true
  try {
    const settings = await api.updateSettings({ licenseKey: licenseKey.value })
    license.value = settings.license || { status: 'none' }
    licenseKey.value = ''
    toast.add({
      title: 'License key saved',
      color: 'success',
      icon: 'i-lucide-circle-check'
    })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to save the license key',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    savingLicense.value = false
  }
}

async function removeLicense() {
  removingLicense.value = true
  try {
    const settings = await api.updateSettings({ licenseKey: '' })
    license.value = settings.license || { status: 'none' }
    toast.add({
      title: 'License key removed',
      color: 'success',
      icon: 'i-lucide-circle-check'
    })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to remove the license key',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    removingLicense.value = false
  }
}

async function saveDefaultTenant() {
  savingDefaultTenant.value = true
  try {
    const settings = await api.updateSettings({
      defaultTenantId: defaultTenantId.value === SELECT_EMPTY ? '' : defaultTenantId.value
    })
    defaultTenantId.value = settings.defaultTenantId || SELECT_EMPTY
    requestLogClientInfo.value = !!settings.requestLogClientInfo
    requestLogClientIp.value = settings.requestLogClientIp || 'off'
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
</script>

<template>
  <div class="space-y-4">
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
          <PField label="Tenant" icon="i-lucide-building-2" class="flex-1">
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
          </PField>
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
          <PField label="Retention" icon="i-lucide-calendar-clock" class="flex-1">
            <UInput
              v-model.number="requestLogRetentionDays"
              type="number"
              min="0"
              max="3650"
              icon="i-lucide-calendar-clock"
              class="font-mono"
              :disabled="!bootstrap?.isSuperAdmin"
            >
              <template #trailing>
                <span class="text-xs text-muted">days</span>
              </template>
            </UInput>
          </PField>
          <UButton
            type="submit"
            :loading="savingLogs"
            icon="i-lucide-save"
            :disabled="!bootstrap?.isSuperAdmin"
          >
            Save
          </UButton>
        </form>

        <USeparator />

        <div class="space-y-1">
          <h3 class="font-medium">Admin UI traffic</h3>
          <p class="max-w-2xl text-sm text-muted">
            Operating the admin UI is itself a stream of HTTP requests
            (<code>/admin/…</code>, <code>/api/admin/…</code>, <code>/api/meta/…</code>, the login
            flow and the UI assets). They are not logged by default, so the log shows the traffic
            of your API instead of Paprika's own bookkeeping. Switch this on when you need an
            audit trail of admin activity. Failed admin requests are logged either way, so a
            rejected login never disappears.
          </p>
        </div>

        <form class="flex max-w-2xl flex-col gap-4" @submit.prevent="saveAdminUiLogging">
          <USwitch
            v-model="requestLogAdminUi"
            label="Log admin UI requests"
            :disabled="!bootstrap?.isSuperAdmin"
          />

          <div>
            <UButton
              type="submit"
              :loading="savingAdminUi"
              icon="i-lucide-save"
              :disabled="!bootstrap?.isSuperAdmin"
            >
              Save
            </UButton>
          </div>
        </form>

        <USeparator />

        <div class="space-y-1">
          <h3 class="font-medium">Client information</h3>
          <p class="max-w-2xl text-sm text-muted">
            User agent and IP address identify the person behind a request, so Paprika does not
            store them unless you switch them on and can justify why you need them. Keep the
            retention above as short as the purpose allows, and prefer the truncated IP: it still
            shows you the network behind abusive traffic. IP addresses are read from
            <code>X-Forwarded-For</code>/<code>X-Real-IP</code>, so they require a reverse proxy
            that sets them.
          </p>
        </div>

        <form class="flex max-w-2xl flex-col gap-4" @submit.prevent="saveClientInfo">
          <USwitch
            v-model="requestLogClientInfo"
            label="Log user agent"
            :disabled="!bootstrap?.isSuperAdmin"
          />

          <PField
            label="Client IP address"
            icon="i-lucide-globe"
            orientation="horizontal"
            help="What is written to the request log for the caller's address."
          >
            <USelect
              v-model="requestLogClientIp"
              :items="clientIpOptions"
              value-key="value"
              label-key="label"
              :disabled="!bootstrap?.isSuperAdmin"
            />
          </PField>

          <div>
            <UButton
              type="submit"
              :loading="savingClientInfo"
              icon="i-lucide-save"
              :disabled="!bootstrap?.isSuperAdmin"
            >
              Save
            </UButton>
          </div>
        </form>
      </div>
    </UCard>

    <UCard>
      <template #header>
        <div class="flex items-center gap-2">
          <UIcon name="i-lucide-badge-check" class="size-5 text-primary" />
          <h2 class="font-semibold">License</h2>
        </div>
      </template>

      <div v-if="loading" class="text-sm text-muted">Loading settings…</div>

      <div v-else class="space-y-4">
        <div class="space-y-2">
          <div class="flex items-center gap-2">
            <UBadge v-if="license.status === 'valid'" color="success" variant="soft">Commercial</UBadge>
            <UBadge v-else color="neutral" variant="soft">Noncommercial</UBadge>
            <UBadge v-if="license.status === 'expired'" color="warning" variant="soft">Expired</UBadge>
            <UBadge v-else-if="license.status === 'invalid'" color="error" variant="soft">Invalid key</UBadge>
          </div>

          <p v-if="license.status === 'valid'" class="max-w-2xl text-sm text-muted">
            This installation is covered by a commercial license and may be used in production for
            commercial purposes within the licensed scope. Support and renewals:
            <a
              href="https://getpaprika.dev"
              target="_blank"
              rel="noopener noreferrer"
              class="text-primary hover:underline"
            >getpaprika.dev</a>.
          </p>

          <p v-else-if="license.status === 'expired'" class="max-w-2xl text-sm text-muted">
            The commercial license for {{ license.licensee }} expired on {{ license.expiresAt }}.
            Until a renewed key is stored, this installation counts as noncommercial. Paprika keeps
            working either way.
          </p>

          <p v-else-if="license.status === 'invalid'" class="max-w-2xl text-sm text-muted">
            The stored license key could not be verified, so this installation counts as
            noncommercial. Paste the key again or ask for a new one.
          </p>

          <p v-if="license.status !== 'valid'" class="max-w-2xl text-sm text-muted">
            Without a commercial license Paprika runs under the
            <a
              href="https://polyformproject.org/licenses/noncommercial/1.0.0"
              target="_blank"
              rel="noopener noreferrer"
              class="text-primary hover:underline"
            >PolyForm Noncommercial License 1.0.0</a>.
            It covers personal and hobby projects, education, research and qualifying nonprofits.
            Companies may also use Paprika for evaluation, development, testing and staging. Running
            it in production for a commercial purpose requires a commercial license per
            installation, which you can request at
            <a
              href="https://getpaprika.dev"
              target="_blank"
              rel="noopener noreferrer"
              class="text-primary hover:underline"
            >getpaprika.dev</a>.
          </p>

          <dl
            v-if="license.status === 'valid' || license.status === 'expired'"
            class="grid max-w-xl grid-cols-[auto_1fr] gap-x-6 gap-y-1 text-sm"
          >
            <dt class="text-muted">Licensed to</dt>
            <dd>{{ license.licensee }}</dd>
            <dt class="text-muted">Installations</dt>
            <dd>{{ license.installations }}</dd>
            <dt class="text-muted">{{ license.status === 'expired' ? 'Expired on' : 'Valid until' }}</dt>
            <dd>{{ license.expiresAt }}</dd>
            <dt class="text-muted">License ID</dt>
            <dd class="font-mono">{{ license.licenseId }}</dd>
          </dl>
        </div>

        <USeparator />

        <div class="space-y-1">
          <h3 class="font-medium">License key</h3>
          <p class="max-w-2xl text-sm text-muted">
            Paste the commercial license key you received. It is checked offline against the public
            key built into Paprika, nothing is sent anywhere.
          </p>
        </div>

        <form class="flex max-w-2xl flex-col gap-4" @submit.prevent="saveLicense">
          <UTextarea
            v-model="licenseKey"
            :rows="3"
            placeholder="PAPRIKA1.…"
            class="w-full font-mono"
            :disabled="!bootstrap?.isSuperAdmin"
          />

          <div class="flex gap-2">
            <UButton
              type="submit"
              :loading="savingLicense"
              icon="i-lucide-save"
              :disabled="!bootstrap?.isSuperAdmin || !licenseKey.trim()"
            >
              Save
            </UButton>
            <UButton
              v-if="license.status !== 'none'"
              color="neutral"
              variant="outline"
              icon="i-lucide-trash-2"
              :loading="removingLicense"
              :disabled="!bootstrap?.isSuperAdmin"
              @click="removeLicense"
            >
              Remove
            </UButton>
          </div>
        </form>
      </div>
    </UCard>
  </div>
</template>
