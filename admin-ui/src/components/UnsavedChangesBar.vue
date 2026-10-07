<script setup lang="ts">
/**
 * The one save control of a settings page: it only shows while the form differs from what is
 * stored, so the page does not need a save button per section and nothing is left half-saved.
 */
withDefaults(defineProps<{ saving?: boolean; saveLabel?: string }>(), { saveLabel: 'Save' })

const emit = defineEmits<{ save: []; discard: [] }>()
</script>

<template>
  <div
    class="sticky bottom-0 z-10 flex flex-wrap items-center justify-between gap-3 rounded-lg border border-default bg-default px-4 py-3 shadow-lg"
  >
    <p class="text-sm font-medium text-default">Unsaved changes</p>
    <div class="flex gap-2">
      <UButton variant="ghost" color="neutral" :disabled="saving" @click="emit('discard')">
        Discard
      </UButton>
      <UButton :loading="saving" icon="i-lucide-save" @click="emit('save')">{{ saveLabel }}</UButton>
    </div>
  </div>
</template>
