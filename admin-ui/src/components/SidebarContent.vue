<script setup lang="ts">
import { ref, watch } from 'vue'
import type { DeepReadonly } from 'vue'
import type { BootstrapData } from '@/types'
import AppLogo from '@/components/AppLogo.vue'
import { SELECT_EMPTY } from '@/lib/utils'
import { selectContentProps, selectMenuUi } from '@/lib/overlay-ui'

type NavItem = {
  label: string
  icon: string
  to: string
  active: boolean
}

const props = defineProps<{
  bootstrap: DeepReadonly<BootstrapData> | null
  generalNavItems: NavItem[]
  tenantNavItems: NavItem[]
  tenantItems: Array<{ label: string; value: string }>
  activeTenantId: string
  activeCollection: string
  mobile?: boolean
}>()

const emit = defineEmits<{
  tenantChange: [tenantId: string]
  newCollection: []
  logout: []
}>()

const generalOpen = ref(true)
const tenantSettingsOpen = ref(true)

watch(
  () => props.generalNavItems.some((item) => item.active),
  (active) => {
    if (active) {
      generalOpen.value = true
    }
  },
  { immediate: true }
)

watch(
  () => props.tenantNavItems.some((item) => item.active),
  (active) => {
    if (active) {
      tenantSettingsOpen.value = true
    }
  },
  { immediate: true }
)

function onTenantChange(value: string) {
  emit('tenantChange', value === SELECT_EMPTY ? '' : value)
}
</script>

<template>
  <div class="flex h-full flex-col">
    <div v-if="!mobile" class="border-b border-default px-4 py-4">
      <AppLogo show-text />
    </div>

    <nav class="flex-1 space-y-4 overflow-auto p-3">
      <section>
        <UCollapsible v-model:open="generalOpen" :unmount-on-hide="false" class="w-full">
          <button
            type="button"
            class="flex w-full items-center justify-between px-2 py-1 text-xs font-medium uppercase tracking-wide text-muted transition-colors hover:text-default"
          >
            <span>General</span>
            <UIcon
              :name="generalOpen ? 'i-lucide-chevron-up' : 'i-lucide-chevron-down'"
              class="size-4"
            />
          </button>

          <template #content>
            <div class="space-y-1 pt-2">
              <UButton
                v-for="item in generalNavItems"
                :key="item.to"
                :to="item.to"
                :icon="item.icon"
                :variant="item.active ? 'subtle' : 'ghost'"
                :color="item.active ? 'neutral' : 'neutral'"
                block
                :class="['justify-start', item.active ? 'font-medium ring-1 ring-inset ring-default' : '']"
              >
                {{ item.label }}
              </UButton>
            </div>
          </template>
        </UCollapsible>
      </section>

      <section v-if="bootstrap?.isSuperAdmin" class="border-t border-default pt-4">
        <label class="mb-2 block px-2 text-xs font-medium uppercase tracking-wide text-muted">
          Active tenant
        </label>
        <USelect
          :model-value="activeTenantId || SELECT_EMPTY"
          :items="[{ label: 'No tenant selected', value: SELECT_EMPTY }, ...tenantItems]"
          placeholder="Select tenant"
          icon="i-lucide-globe"
          :ui="selectMenuUi"
          :content="selectContentProps"
          class="w-full"
          @update:model-value="onTenantChange"
        />
      </section>

      <section class="border-t border-default pt-4">
        <UCollapsible v-model:open="tenantSettingsOpen" :unmount-on-hide="false" class="w-full">
          <button
            type="button"
            class="flex w-full items-center justify-between px-2 py-1 text-xs font-medium uppercase tracking-wide text-muted transition-colors hover:text-default"
          >
            <span>Tenant settings</span>
            <UIcon
              :name="tenantSettingsOpen ? 'i-lucide-chevron-up' : 'i-lucide-chevron-down'"
              class="size-4"
            />
          </button>

          <template #content>
            <div class="space-y-1 pt-2">
              <UButton
                v-for="item in tenantNavItems"
                :key="item.to"
                :to="item.to"
                :icon="item.icon"
                :variant="item.active ? 'subtle' : 'ghost'"
                :color="item.active ? 'neutral' : 'neutral'"
                block
                :class="['justify-start', item.active ? 'font-medium ring-1 ring-inset ring-default' : '']"
              >
                {{ item.label }}
              </UButton>
            </div>
          </template>
        </UCollapsible>
      </section>

      <section class="border-t border-default pt-4">
        <div class="px-2 pb-2 text-xs font-medium uppercase tracking-wide text-muted">
          Tenant collections
        </div>

        <template v-if="bootstrap?.hasActiveTenant">
          <div class="space-y-1">
            <UButton
              icon="i-lucide-plus"
              block
              class="justify-start"
              @click="emit('newCollection')"
            >
              New collection
            </UButton>

            <UButton
              v-for="collection in bootstrap.collections"
              :key="collection"
              :to="`/admin/collections/${collection}/data`"
              :variant="activeCollection === collection ? 'subtle' : 'ghost'"
              color="neutral"
              icon="i-lucide-database"
              block
              :class="[
                'justify-start font-mono text-sm',
                activeCollection === collection ? 'font-medium ring-1 ring-inset ring-default' : ''
              ]"
            >
              {{ collection }}
            </UButton>
          </div>
        </template>

        <p v-else class="px-2 text-sm text-muted">
          {{
            bootstrap?.isSuperAdmin
              ? 'Select a tenant above to manage collections.'
              : 'No tenant context available.'
          }}
        </p>
      </section>
    </nav>

    <div class="mt-auto border-t border-default p-3">
      <UButton
        icon="i-lucide-log-out"
        color="error"
        block
        class="justify-start"
        @click="emit('logout')"
      >
        Log out
      </UButton>
    </div>
  </div>
</template>
