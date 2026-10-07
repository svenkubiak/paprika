<script setup lang="ts">
/**
 * A stored timestamp the way PocketBase's admin shows one: the UTC date above the UTC time, so a
 * column reads the same for everyone and lines up across rows, with the viewer's local time and
 * zone on hover. A value that does not parse is shown as it is.
 */
import { computed } from 'vue'
import { formatLocalTimestamp, formatTimeZone } from '@/lib/utils'

const props = withDefaults(defineProps<{ value?: string | null; empty?: string }>(), {
  empty: '—'
})

const parsed = computed(() => {
  if (!props.value) return null
  const date = new Date(props.value)
  return Number.isNaN(date.getTime()) ? null : date
})
const iso = computed(() => parsed.value?.toISOString() ?? '')
</script>

<template>
  <span v-if="!value" class="text-sm text-muted">{{ empty }}</span>
  <span v-else-if="!parsed" class="font-mono text-sm">{{ value }}</span>
  <span
    v-else
    class="inline-flex flex-col whitespace-nowrap leading-tight"
    :title="`${formatLocalTimestamp(value)} ${formatTimeZone(value)}`"
  >
    <span class="text-sm text-default">{{ iso.slice(0, 10) }}</span>
    <span class="text-xs text-muted">{{ iso.slice(11, 19) }} UTC</span>
  </span>
</template>
