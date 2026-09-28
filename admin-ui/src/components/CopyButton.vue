<script setup lang="ts">
/**
 * Copies a value and says so on the button itself.
 *
 * Record ids, API keys and hook secrets exist to be pasted somewhere else, so they get the same
 * button everywhere. The confirmation is the icon swap rather than a toast: a toast for something
 * the user can see happened is one notification too many.
 */
import { onBeforeUnmount, ref } from 'vue'
import { copyToClipboard } from '@/lib/utils'

const props = withDefaults(
  defineProps<{
    value: string
    label?: string
    size?: 'xs' | 'sm' | 'md'
  }>(),
  { size: 'sm' }
)

const copied = ref(false)
let resetTimer: number | undefined

async function copy() {
  try {
    await copyToClipboard(props.value)
    copied.value = true
    window.clearTimeout(resetTimer)
    resetTimer = window.setTimeout(() => {
      copied.value = false
    }, 1500)
  } catch {
    // Clipboard access can be denied by the browser; nothing useful to do but leave the icon be.
  }
}

onBeforeUnmount(() => window.clearTimeout(resetTimer))
</script>

<template>
  <UButton
    :size="size"
    variant="ghost"
    :color="copied ? 'success' : 'neutral'"
    :icon="copied ? 'i-lucide-check' : 'i-lucide-copy'"
    :aria-label="copied ? 'Copied' : label || 'Copy value'"
    :title="label || 'Copy value'"
    :disabled="!value"
    @click="copy"
  />
</template>
