<script setup lang="ts">
import { computed, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    label?: string
    icon?: string
    /**
     * Required fields carry no marker since they are the majority. Set this where leaving the field
     * empty is a decision; leave it off in blocks that are optional throughout.
     */
    optional?: boolean
    /** One sentence, always visible below the control. */
    help?: string
    /** The rest of the explanation, behind the info toggle beside the label. */
    details?: string
    error?: string
    /** 'vertical' for sheets, 'horizontal' for pages. */
    orientation?: 'vertical' | 'horizontal'
    /** 'sm' for numbers and flags, 'md' for identifiers and dates, 'full' for text. */
    width?: 'sm' | 'md' | 'full'
    hint?: string
    counter?: string
    counterExceeded?: boolean
  }>(),
  {
    orientation: 'vertical',
    width: 'full'
  }
)

const detailsOpen = ref(false)

const widthClass = computed(() => {
  if (props.width === 'sm') return 'w-full max-w-36'
  if (props.width === 'md') return 'w-full max-w-72'
  return 'w-full'
})

const hasFooter = computed(() => Boolean(props.hint || props.counter))
const hasHelpArea = computed(() => Boolean(props.help || props.details || hasFooter.value))

/**
 * The horizontal Nuxt UI variant is a plain `flex justify-between`, which would put label and
 * control on one line on a phone too; the label column only splits off once there is room.
 */
const fieldUi = computed(() => ({
  root:
    props.orientation === 'horizontal'
      ? 'w-full flex flex-col gap-1.5 sm:flex-row sm:items-start sm:justify-between sm:gap-6'
      : 'w-full',
  wrapper: props.orientation === 'horizontal' ? 'sm:w-64 sm:shrink-0 sm:pt-1.5' : '',
  container: props.orientation === 'horizontal' ? 'min-w-0 flex-1' : 'mt-1',
  labelWrapper: 'flex content-center items-center justify-between gap-2',
  label: 'block text-sm font-medium text-default',
  hint: 'text-xs font-normal text-dimmed',
  help: hasHelpArea.value ? 'mt-1.5 space-y-1.5 text-xs text-muted' : 'hidden',
  error: 'mt-1.5 space-y-1.5 text-xs font-medium text-error'
}))
</script>

<template>
  <UFormField
    :error="error"
    :hint="optional ? 'optional' : undefined"
    :orientation="orientation"
    :ui="fieldUi"
  >
    <template v-if="label || icon" #label>
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
          @click="detailsOpen = !detailsOpen"
        >
          <UIcon name="i-lucide-info" class="size-3.5" />
        </button>
      </span>
    </template>

    <div :class="widthClass">
      <slot />
    </div>

    <!--
      UFormField renders either the help or the error slot, never both, so the trailing block
      (details panel, counter) has to exist in each.
    -->
    <template v-if="hasHelpArea" #help>
      <span v-if="help" class="block">{{ help }}</span>
      <p
        v-if="details && detailsOpen"
        class="rounded-e border-s-2 border-primary bg-elevated px-2.5 py-1.5 text-xs text-muted"
      >
        {{ details }}
      </p>
      <div v-if="hasFooter" class="flex items-center gap-2 text-[11px] text-dimmed">
        <span v-if="hint">{{ hint }}</span>
        <span v-if="counter" class="ms-auto tabular-nums" :class="{ 'font-semibold text-error': counterExceeded }">
          {{ counter }}
        </span>
      </div>
    </template>

    <!-- Only declared when set: UFormField shows its error block whenever the slot exists. -->
    <template v-if="error" #error="{ error: message }">
      <span class="block">{{ message }}</span>
      <p
        v-if="details && detailsOpen"
        class="rounded-e border-s-2 border-primary bg-elevated px-2.5 py-1.5 text-xs font-normal text-muted"
      >
        {{ details }}
      </p>
      <div v-if="hasFooter" class="flex items-center gap-2 text-[11px] font-normal text-dimmed">
        <span v-if="hint">{{ hint }}</span>
        <span v-if="counter" class="ms-auto tabular-nums" :class="{ 'font-semibold text-error': counterExceeded }">
          {{ counter }}
        </span>
      </div>
    </template>
  </UFormField>
</template>
