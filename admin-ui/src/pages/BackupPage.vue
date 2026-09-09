<script setup lang="ts">
import { ref } from 'vue'
import { api } from '@/lib/api'
import { modalUi } from '@/lib/overlay-ui'
import { useAppToast } from '@/composables/useAppToast'
import { useBootstrap } from '@/composables/useBootstrap'

const toast = useAppToast()
const { bootstrap } = useBootstrap()

const importFile = ref<File | null>(null)
const importLoading = ref(false)
const importOpen = ref(false)

function onImportFileSelected(event: Event) {
  const input = event.target as HTMLInputElement
  importFile.value = input.files?.[0] ?? null
}

async function confirmImport() {
  if (!importFile.value) return
  importLoading.value = true
  try {
    const result = await api.importBackup(importFile.value)
    importOpen.value = false
    importFile.value = null
    toast.add({
      title: `Backup restored: ${result.tenants} tenant(s), ${result.documents} document(s), ${result.files} file(s)`,
      color: 'success',
      icon: 'i-lucide-circle-check'
    })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Import failed',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    importLoading.value = false
  }
}
</script>

<template>
  <div class="space-y-4">
    <UCard>
      <div class="space-y-6">
        <div class="space-y-2">
          <h3 class="font-medium">Export backup</h3>
          <p class="max-w-2xl text-sm text-muted">
            Download a complete backup of all tenants, collections, data, and uploaded files as a ZIP archive.
          </p>
          <UButton
            icon="i-lucide-download"
            :disabled="!bootstrap?.isSuperAdmin"
            @click="api.exportBackup()"
          >
            Download backup
          </UButton>
        </div>

        <USeparator />

        <div class="space-y-2">
          <h3 class="font-medium">Restore from backup</h3>
          <p class="max-w-2xl text-sm text-muted">
            Upload a previously exported ZIP to restore all data. This will permanently overwrite everything.
          </p>
          <div class="flex flex-wrap items-center gap-3">
            <input
              type="file"
              accept=".zip"
              :disabled="!bootstrap?.isSuperAdmin"
              class="text-sm text-default cursor-pointer file:mr-3 file:cursor-pointer file:rounded-md file:border-0 file:bg-elevated file:px-3 file:py-1.5 file:text-sm file:font-medium file:text-default"
              @change="onImportFileSelected"
            />
            <UButton
              color="error"
              variant="soft"
              icon="i-lucide-upload"
              :disabled="!importFile || !bootstrap?.isSuperAdmin"
              @click="importOpen = true"
            >
              Restore backup
            </UButton>
          </div>
        </div>
      </div>
    </UCard>
  </div>

  <UModal v-model:open="importOpen" :ui="modalUi">
    <template #content>
      <UCard>
        <template #header>
          <h3 class="font-semibold">Restore backup</h3>
        </template>

        <div class="space-y-4">
          <UAlert
            color="error"
            variant="soft"
            icon="i-lucide-triangle-alert"
            title="This action cannot be undone"
            description="All existing tenants, collections, data, and files will be permanently overwritten with the backup contents."
          />
          <p class="text-sm text-muted">
            File: <span class="font-medium">{{ importFile?.name }}</span>
          </p>
        </div>

        <template #footer>
          <div class="flex justify-end gap-2">
            <UButton variant="ghost" color="neutral" @click="importOpen = false">Cancel</UButton>
            <UButton
              color="error"
              :loading="importLoading"
              icon="i-lucide-upload"
              @click="confirmImport"
            >
              Restore backup
            </UButton>
          </div>
        </template>
      </UCard>
    </template>
  </UModal>
</template>
