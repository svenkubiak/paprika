<script setup lang="ts">
import { onMounted } from 'vue'
import AppHeader from '@/components/AppHeader.vue'
import AppSidebar from '@/components/AppSidebar.vue'
import CollectionCreatorModal from '@/components/CollectionCreatorModal.vue'
import CollectionDeleteModal from '@/components/CollectionDeleteModal.vue'
import { useBootstrap } from '@/composables/useBootstrap'
import { useAppToast } from '@/composables/useAppToast'

const { load, consumeDefaultTenantNotice } = useBootstrap()
const toast = useAppToast()

onMounted(async () => {
  await load()
  const tenantName = consumeDefaultTenantNotice()
  if (tenantName) {
    toast.add({
      title: `Default tenant "${tenantName}" was selected.`,
      description: 'You can change the tenant anytime in the sidebar.',
      color: 'primary',
      icon: 'i-lucide-building-2',
      duration: 5500
    })
  }
})
</script>

<template>
  <div class="flex h-svh w-full overflow-hidden bg-muted/30">
    <AppSidebar />
    <div class="flex min-w-0 flex-1 flex-col overflow-hidden">
      <AppHeader />
      <main class="flex-1 overflow-y-auto p-4 md:p-6">
        <slot />
      </main>
    </div>
    <CollectionCreatorModal />
    <CollectionDeleteModal />
  </div>
</template>
