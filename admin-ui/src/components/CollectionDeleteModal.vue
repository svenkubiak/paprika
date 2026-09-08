<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/lib/api'
import { useBootstrap } from '@/composables/useBootstrap'
import { useAppToast } from '@/composables/useAppToast'
import { modalUi } from '@/lib/overlay-ui'

const router = useRouter()
const toast = useAppToast()
const { load } = useBootstrap()

const open = ref(false)
const collection = ref('')
const deleting = ref(false)

function onOpen(event: Event) {
  const detail = (event as CustomEvent<{ collection?: string }>).detail
  const name = detail?.collection?.trim()
  if (!name) return
  collection.value = name
  open.value = true
}

async function deleteCollection() {
  if (!collection.value) return
  deleting.value = true
  try {
    const definition = await api.getCollectionDefinition(collection.value)
    if (definition.system) {
      toast.add({
        title: 'System collections cannot be deleted',
        color: 'error',
        icon: 'i-lucide-circle-x'
      })
      return
    }
    await api.deleteCollectionDefinition(collection.value, definition.id)
    open.value = false
    await load(true)
    toast.add({ title: 'Collection deleted', color: 'success', icon: 'i-lucide-circle-check' })
    await router.push('/')
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to delete collection',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    deleting.value = false
  }
}

onMounted(() => {
  window.addEventListener('paprika:delete-collection', onOpen)
})

onUnmounted(() => {
  window.removeEventListener('paprika:delete-collection', onOpen)
})
</script>

<template>
  <UModal v-model:open="open" portal="body" :ui="modalUi">
    <template #content>
      <UCard>
        <template #header>
          <div class="flex items-center gap-2">
            <UIcon name="i-lucide-triangle-alert" class="size-5 text-error" />
            <h3 class="font-semibold">Delete collection</h3>
          </div>
        </template>

        <p class="text-sm text-muted">
          Delete collection "{{ collection }}" and all its data? This cannot be undone.
        </p>

        <template #footer>
          <div class="flex justify-end gap-2">
            <UButton variant="ghost" color="neutral" @click="open = false">Cancel</UButton>
            <UButton color="error" :loading="deleting" icon="i-lucide-trash-2" @click="deleteCollection">
              Delete collection
            </UButton>
          </div>
        </template>
      </UCard>
    </template>
  </UModal>
</template>
