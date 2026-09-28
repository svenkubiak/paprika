<script setup lang="ts">
/**
 * A yes/no setting as a full row: subject and reason on the left, the switch on the right, the
 * whole row clickable.
 *
 * This replaces the `<div class="flex justify-between"><label/><USwitch/></div>` pattern that grew
 * in every sheet. Besides being one shape instead of six, the row states *why* the setting exists
 * - a switch labelled "Cascade delete" without its consequence is the kind of thing that gets
 * turned on once and regretted later.
 *
 * `USwitch` renders its thumb before the label, so the row order is flipped in `ui.root` rather
 * than rebuilt by hand: that keeps reka-ui's label/control wiring, and with it keyboard operation
 * and the click target, intact.
 */
import { ref } from 'vue'

defineProps<{
  modelValue: boolean
  label: string
  /** Icon in front of the label. */
  icon?: string
  /** One sentence on what the setting does, shown under the label. */
  help?: string
  /** The rest of the explanation, revealed by the info toggle. */
  details?: string
  disabled?: boolean
}>()

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const detailsOpen = ref(false)
</script>

<template>
  <div class="w-full">
    <USwitch
      :model-value="modelValue"
      :description="help"
      :disabled="disabled"
      :ui="{
        root: 'w-full flex-row-reverse items-start justify-between gap-4 rounded-md border border-default p-3 transition-colors hover:bg-elevated has-disabled:hover:bg-transparent',
        wrapper: 'min-w-0 flex-1',
        label: 'text-sm font-medium text-default',
        description: 'text-xs text-muted'
      }"
      @update:model-value="emit('update:modelValue', $event as boolean)"
    >
      <template #label>
        <span class="inline-flex items-center gap-1.5">
          <UIcon v-if="icon" :name="icon" class="size-3.5 shrink-0 text-dimmed" />
          <span>{{ label }}</span>
          <button
            v-if="details"
            type="button"
            class="inline-flex rounded-sm text-dimmed transition-colors hover:text-primary focus:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
            :class="{ 'text-primary': detailsOpen }"
            :aria-expanded="detailsOpen"
            :aria-label="detailsOpen ? 'Hide details' : 'Show details'"
            tabindex="-1"
            @click.stop.prevent="detailsOpen = !detailsOpen"
          >
            <UIcon name="i-lucide-info" class="size-3.5" />
          </button>
        </span>
      </template>
    </USwitch>

    <p
      v-if="details && detailsOpen"
      class="mt-1.5 rounded-e border-s-2 border-primary bg-elevated px-2.5 py-1.5 text-xs text-muted"
    >
      {{ details }}
    </p>
  </div>
</template>
