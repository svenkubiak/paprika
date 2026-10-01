<script setup lang="ts">
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
    // Clipboard access can be denied by the browser; nothing useful to do.
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
