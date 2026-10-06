<script setup lang="ts">
/**
 * The action buttons of a table row, so every table offers them in the same order and style:
 * Edit first, table-specific actions from the default slot, Delete last. Edit and Delete are
 * listener props rather than emits, so a table without one of them simply leaves it out.
 */
withDefaults(
  defineProps<{
    deleteLabel?: string
    deleteDisabled?: boolean
    onEdit?: () => void
    onDelete?: () => void
  }>(),
  { deleteLabel: 'Delete' }
)
</script>

<template>
  <div class="flex justify-end gap-2" @click.stop>
    <UButton v-if="onEdit" size="sm" variant="soft" icon="i-lucide-pencil" @click="onEdit">
      Edit
    </UButton>
    <slot />
    <UButton
      v-if="onDelete"
      size="sm"
      color="error"
      variant="soft"
      icon="i-lucide-trash-2"
      :disabled="deleteDisabled"
      @click="onDelete"
    >
      {{ deleteLabel }}
    </UButton>
  </div>
</template>
