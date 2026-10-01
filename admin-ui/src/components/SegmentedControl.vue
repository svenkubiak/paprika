<script setup lang="ts">
withDefaults(
  defineProps<{
    modelValue: string
    items: { label: string; value: string; icon?: string }[]
    size?: 'xs' | 'sm'
    /** Renders the active option in a quieter style, for "no value" entries. */
    mutedValue?: string
    ariaLabel?: string
  }>(),
  { size: 'sm' }
)

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
</script>

<template>
  <div
    class="inline-flex gap-0.5 rounded-md border border-default bg-elevated p-0.5"
    role="group"
    :aria-label="ariaLabel"
  >
    <UButton
      v-for="item in items"
      :key="item.value"
      :size="size"
      :icon="item.icon"
      :color="modelValue === item.value ? 'primary' : 'neutral'"
      :variant="modelValue === item.value ? 'soft' : 'ghost'"
      :class="[
        'justify-center',
        modelValue === item.value && item.value === mutedValue ? 'italic text-muted' : ''
      ]"
      :aria-pressed="modelValue === item.value"
      @click="emit('update:modelValue', item.value)"
    >
      {{ item.label }}
    </UButton>
  </div>
</template>
