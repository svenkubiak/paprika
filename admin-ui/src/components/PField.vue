<script setup lang="ts">
/**
 * The field wrapper every form in the admin uses.
 *
 * It fixes the anatomy of a field so that the same decisions do not get made again at every call
 * site: an icon and the label on top, "optional" instead of a required marker, one sentence of
 * help below the control, the rest of the explanation behind a toggle, and a footer for whatever
 * the schema already knows (a limit, a counter, a parsed value).
 *
 * Everything is built on `UFormField`, which already provides the pieces that matter for
 * accessibility and for the error state: it labels the control, it wires up the description, and
 * it turns the input red through the form-field injection as soon as `error` is set. The wrapper
 * only adds conventions on top.
 */
import { computed, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    label?: string
    /** Icon in front of the label - the field type for schema fields, the subject otherwise. */
    icon?: string
    /**
     * Marks the field as not required. Required fields carry no marker on purpose: in our forms
     * they are the majority, which makes the exception the more useful thing to point out.
     *
     * Set it where a form mixes required and optional fields and leaving one empty is a decision
     * - a record field the schema does not require, an email nobody has to give. Leave it off in
     * blocks that are options throughout, such as the constraints of the schema editor: marking
     * all of them says nothing, and "empty means no limit" is what the help line is for.
     */
    optional?: boolean
    /** One sentence, always visible below the control. */
    help?: string
    /** The rest of the explanation, revealed by the info toggle beside the label. */
    details?: string
    /** Validation message. Takes the place of the help line while it is set. */
    error?: string
    /** 'vertical' = label above the control (sheets), 'horizontal' = label beside it (pages). */
    orientation?: 'vertical' | 'horizontal'
    /** Control width: 'sm' for numbers and flags, 'md' for identifiers and dates, 'full' for text. */
    width?: 'sm' | 'md' | 'full'
    /** Left half of the footer: a constraint, an example, a parsed value. */
    hint?: string
    /** Right half of the footer, e.g. a character counter. */
    counter?: string
    /** Renders the counter as exceeded. */
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
 * The horizontal variant of the Nuxt UI theme is a plain `flex justify-between`, which would put
 * a long label and its control on one line on a phone as well. The label column only splits off
 * once there is room for it.
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
      The help and the error slot carry the same trailing block: the details panel stays open
      across a failed save, and a counter is at its most useful exactly when the value is too
      long. `UFormField` renders one or the other, never both, so the block has to exist twice.
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

    <!--
      Only declared when there is a message: `UFormField` renders its error block as soon as an
      error *slot* exists, so an unconditional one would take the place of the help line for good.
    -->
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
