<script setup lang="ts">
defineProps<{
  label: string
  hint?: string
}>()
</script>

<template>
  <span class="inline-flex items-center gap-1.5">
    <span>{{ label }}</span>
    <UTooltip
      v-if="hint"
      :text="hint"
      :content="{ side: 'top' }"
      :ui="{ content: 'bg-black text-white', arrow: 'fill-black stroke-black' }"
    >
      <button
        type="button"
        class="inline-flex rounded-sm text-muted transition-colors hover:text-default focus:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
        tabindex="-1"
        aria-label="Field help"
        @click.prevent
      >
        <UIcon name="i-lucide-circle-question-mark" class="size-3.5" />
      </button>
    </UTooltip>
  </span>
</template>
