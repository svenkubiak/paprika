<script setup lang="ts">
import { ref } from 'vue'
import { api } from '@/lib/api'
import { useAppToast } from '@/composables/useAppToast'

const toast = useAppToast()
const importingSchema = ref(false)
const schemaFileInput = ref<HTMLInputElement | null>(null)

async function handleSchemaImport(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  if (schemaFileInput.value) schemaFileInput.value.value = ''

  importingSchema.value = true
  try {
    const result = await api.importSchema(file)
    toast.add({
      title: `Schema imported: ${result.collectionsCreated} created, ${result.collectionsUpdated} updated, ${result.hooksRestored} hooks restored`,
      color: 'success',
      icon: 'i-lucide-circle-check'
    })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Schema import failed',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    importingSchema.value = false
  }
}
</script>

<template>
  <div class="space-y-4">
    <UCard>
      <template #header>
        <div class="flex items-center gap-2">
          <UIcon name="i-lucide-file-json" class="size-5 text-primary" />
          <h2 class="font-semibold">Schema</h2>
        </div>
      </template>

      <div class="space-y-4">
        <div class="space-y-1">
          <h3 class="font-medium">Export schema</h3>
          <p class="max-w-2xl text-sm text-muted">
            Download all collection definitions, rules, and hook configurations for the active tenant as JSON. No data is included.
          </p>
        </div>
        <UButton icon="i-lucide-download" @click="api.exportSchema()">Export schema</UButton>

        <USeparator />

        <div class="space-y-1">
          <h3 class="font-medium">Import schema</h3>
          <p class="max-w-2xl text-sm text-muted">
            Apply a previously exported schema to the active tenant. Existing collections are updated, new ones are created. All hooks are replaced. No data is modified.
          </p>
        </div>
        <input
          ref="schemaFileInput"
          type="file"
          accept=".json,application/json"
          class="hidden"
          @change="handleSchemaImport"
        />
        <UButton
          icon="i-lucide-upload"
          :loading="importingSchema"
          @click="schemaFileInput?.click()"
        >
          Import schema
        </UButton>
      </div>
    </UCard>
  </div>
</template>
