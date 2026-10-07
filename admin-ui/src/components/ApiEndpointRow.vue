<script setup lang="ts">
/**
 * One endpoint of the API reference: method, path and who may call it at a glance, the example
 * request and success response when expanded. Error responses are listed once for the whole page.
 * The access badge and the copy button sit beside the toggle, not inside it: a link or button
 * nested in a button is invalid and unreachable by keyboard.
 */
import { computed, ref, useId } from 'vue'
import { methodColor, type ApiEndpointDoc } from '@/lib/collection-api-docs'
import type { RuleLevelChoice } from '@/lib/rule-levels'
import ApiEndpointExamples from '@/components/ApiEndpointExamples.vue'

const props = defineProps<{
  endpoint: ApiEndpointDoc
  /** The rule level that guards the endpoint; links to the Rules tab. */
  access?: RuleLevelChoice
  rulesLink?: string
}>()

const emit = defineEmits<{ copy: [text: string] }>()

const open = ref(false)
const contentId = useId()
const examples = computed(() => props.endpoint.examples.filter((example) => example.variant !== 'error'))
</script>

<template>
  <div>
    <div class="flex items-center gap-2 px-4 sm:px-6">
      <button
        type="button"
        class="flex min-w-0 flex-1 items-center gap-3 py-3 text-left"
        :aria-expanded="open"
        :aria-controls="contentId"
        @click="open = !open"
      >
        <UBadge :color="methodColor(endpoint.method)" variant="soft" class="w-16 shrink-0 justify-center">
          {{ endpoint.method }}
        </UBadge>
        <code class="min-w-0 flex-1 truncate text-sm">{{ endpoint.path }}</code>
        <UIcon
          :name="open ? 'i-lucide-chevron-up' : 'i-lucide-chevron-down'"
          class="size-4 shrink-0 text-muted"
        />
      </button>
      <RouterLink
        v-if="access && rulesLink"
        :to="rulesLink"
        class="hidden shrink-0 rounded-md sm:inline-flex"
        :title="`${access.description} Change it on the Rules tab.`"
      >
        <UBadge color="neutral" variant="soft" :icon="access.icon">{{ access.label }}</UBadge>
      </RouterLink>
      <UButton
        size="xs"
        variant="ghost"
        color="neutral"
        icon="i-lucide-copy"
        aria-label="Copy endpoint"
        @click="emit('copy', `${endpoint.method} ${endpoint.path}`)"
      />
    </div>

    <div
      v-show="open"
      :id="contentId"
      class="space-y-4 border-t border-default bg-muted/15 px-4 py-4 sm:px-6"
    >
      <p class="text-sm text-toned">{{ endpoint.summary }}</p>
      <ApiEndpointExamples :examples="examples" @copy="emit('copy', $event)" />
    </div>
  </div>
</template>
