<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/lib/api'
import { useBootstrap } from '@/composables/useBootstrap'
import { useAppToast } from '@/composables/useAppToast'
import FieldLabelHelp from '@/components/FieldLabelHelp.vue'
import { modalUi } from '@/lib/overlay-ui'

const router = useRouter()
const toast = useAppToast()
const { bootstrap, load } = useBootstrap()

const open = ref(false)
const name = ref('')
const creating = ref(false)

function normalizeCollectionName(value: string) {
  return value.trim().toLowerCase().replace(/\s+/g, '_').replace(/[^a-z0-9_]/g, '')
}

function onOpen() {
  if (!bootstrap.value?.hasActiveTenant) {
    toast.add({
      title: 'Select a tenant before creating collections',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
    return
  }
  name.value = ''
  open.value = true
}

async function createCollection() {
  const normalized = normalizeCollectionName(name.value)
  if (!normalized) {
    toast.add({ title: 'Collection name is required', color: 'error', icon: 'i-lucide-circle-x' })
    return
  }
  if (bootstrap.value?.collections.includes(normalized)) {
    toast.add({ title: 'Collection already exists', color: 'error', icon: 'i-lucide-circle-x' })
    return
  }

  creating.value = true
  try {
    await api.createCollectionDefinition(normalized)
    open.value = false
    await load(true)
    toast.add({
      title: `Collection "${normalized}" created`,
      color: 'success',
      icon: 'i-lucide-circle-check'
    })
    await router.push(`/admin/collections/${normalized}/schema`)
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to create collection',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    creating.value = false
  }
}

onMounted(() => {
  window.addEventListener('paprika:new-collection', onOpen)
})

onUnmounted(() => {
  window.removeEventListener('paprika:new-collection', onOpen)
})
</script>

<template>
  <UModal v-model:open="open" portal="body" :ui="modalUi">
    <template #content>
      <UCard>
        <template #header>
          <div class="flex items-center gap-2">
            <UIcon name="i-lucide-database" class="size-5 text-primary" />
            <h3 class="font-semibold">New collection</h3>
          </div>
        </template>

        <p class="mb-4 text-sm text-muted">
          Creates a new collection with an empty schema. Fields can be added on the Schema tab.
        </p>

        <form class="w-full space-y-4" @submit.prevent="createCollection">
          <UFormField required class="w-full">
            <template #label>
              <FieldLabelHelp
                label="Name"
                hint="Lowercase letters, numbers, and underscores. Fields are added on the Schema tab after creation."
              />
            </template>
            <UInput v-model="name" icon="i-lucide-database" class="w-full font-mono" autofocus />
          </UFormField>
          <div class="flex justify-end gap-2">
            <UButton variant="ghost" color="neutral" @click="open = false">Cancel</UButton>
            <UButton type="submit" :loading="creating" icon="i-lucide-plus">
              Create collection
            </UButton>
          </div>
        </form>
      </UCard>
    </template>
  </UModal>
</template>
