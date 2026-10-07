<script setup lang="ts">
/**
 * Asks for the name of what is about to be deleted before an irreversible delete is allowed, so a
 * misplaced click cannot remove a tenant or collection. Enter confirms once the phrase matches.
 */
import { computed, useId } from 'vue'

const props = defineProps<{ expected: string }>()
const model = defineModel<string>({ required: true })
const emit = defineEmits<{ confirm: [] }>()

const id = useId()
const matches = computed(() => model.value === props.expected)

function onEnter() {
  if (matches.value) emit('confirm')
}
</script>

<template>
  <div class="mt-4 space-y-1.5">
    <label :for="id" class="block text-sm text-default">
      Type <code class="font-mono font-semibold">{{ expected }}</code> to confirm
    </label>
    <UInput
      :id="id"
      v-model="model"
      class="w-full"
      autocomplete="off"
      spellcheck="false"
      @keydown.enter.prevent="onEnter"
    />
  </div>
</template>
