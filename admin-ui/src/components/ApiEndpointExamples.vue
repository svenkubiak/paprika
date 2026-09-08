<script setup lang="ts">
import type { ApiExampleBlock } from '@/lib/collection-api-docs'
import { computed } from 'vue'

const props = defineProps<{
  examples: ApiExampleBlock[]
}>()

const emit = defineEmits<{
  copy: [text: string]
}>()

const requestExamples = computed(() =>
  props.examples.filter((example) => example.variant !== 'success' && example.variant !== 'error')
)

const responseExamples = computed(() =>
  props.examples.filter((example) => example.variant === 'success' || example.variant === 'error')
)

function exampleBlockClass(variant?: ApiExampleBlock['variant']) {
  if (variant === 'error') {
    return { card: 'border-error/30', header: 'border-error/20 bg-error/5' }
  }
  if (variant === 'success') {
    return { card: 'border-success/30', header: 'border-success/20 bg-success/5' }
  }
  return { card: 'border-primary/25', header: 'border-primary/15 bg-primary/5' }
}
</script>

<template>
  <div class="space-y-5">
    <section v-if="requestExamples.length" class="space-y-3">
      <div class="flex items-center gap-2">
        <div
          class="flex size-7 items-center justify-center rounded-md bg-primary/10 text-primary"
        >
          <UIcon name="i-lucide-arrow-up-right" class="size-4" />
        </div>
        <h3 class="text-sm font-semibold">Request</h3>
      </div>

      <div class="space-y-3 rounded-xl border border-primary/20 bg-primary/5 p-3 sm:p-4">
        <div
          v-for="(example, index) in requestExamples"
          :key="`request-${example.title}-${index}`"
          class="overflow-hidden rounded-lg border bg-default"
          :class="exampleBlockClass(example.variant).card"
        >
          <div
            class="flex items-start justify-between gap-3 border-b px-3 py-2"
            :class="exampleBlockClass(example.variant).header"
          >
            <div class="min-w-0">
              <p class="text-sm font-medium">{{ example.title }}</p>
              <p v-if="example.description" class="text-xs text-muted">{{ example.description }}</p>
            </div>
            <UButton
              size="xs"
              variant="soft"
              color="neutral"
              icon="i-lucide-copy"
              @click="emit('copy', example.code)"
            >
              Copy
            </UButton>
          </div>
          <pre class="overflow-x-auto p-3 font-mono text-xs leading-relaxed"><code>{{ example.code }}</code></pre>
        </div>
      </div>
    </section>

    <section v-if="responseExamples.length" class="space-y-3">
      <div class="flex items-center gap-2">
        <div
          class="flex size-7 items-center justify-center rounded-md bg-muted/80 text-muted"
        >
          <UIcon name="i-lucide-arrow-down-left" class="size-4" />
        </div>
        <h3 class="text-sm font-semibold">Responses</h3>
      </div>

      <div
        class="space-y-3 rounded-xl border border-dashed border-default bg-default/60 p-3 sm:p-4"
      >
        <div
          v-for="(example, index) in responseExamples"
          :key="`response-${example.title}-${example.variant}-${index}`"
          class="overflow-hidden rounded-lg border bg-default"
          :class="exampleBlockClass(example.variant).card"
        >
          <div
            class="flex items-start justify-between gap-3 border-b px-3 py-2"
            :class="exampleBlockClass(example.variant).header"
          >
            <div class="min-w-0">
              <div class="flex items-center gap-2">
                <p class="text-sm font-medium">{{ example.title }}</p>
                <UBadge
                  v-if="example.variant === 'error'"
                  color="error"
                  variant="soft"
                  size="xs"
                >
                  Error
                </UBadge>
                <UBadge
                  v-else-if="example.variant === 'success'"
                  color="success"
                  variant="soft"
                  size="xs"
                >
                  Success
                </UBadge>
              </div>
              <p v-if="example.description" class="text-xs text-muted">{{ example.description }}</p>
            </div>
            <UButton
              size="xs"
              variant="soft"
              color="neutral"
              icon="i-lucide-copy"
              @click="emit('copy', example.code)"
            >
              Copy
            </UButton>
          </div>
          <pre class="overflow-x-auto p-3 font-mono text-xs leading-relaxed"><code>{{ example.code }}</code></pre>
        </div>
      </div>
    </section>
  </div>
</template>
