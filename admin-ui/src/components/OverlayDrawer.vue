<script setup lang="ts">
import { ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    open: boolean
    side?: 'left' | 'right'
    widthClass?: string
    overlayClass?: string
    labelledBy?: string
  }>(),
  {
    side: 'right',
    widthClass: 'w-full max-w-md'
  }
)

const emit = defineEmits<{
  'update:open': [value: boolean]
}>()

const openedAt = ref(0)

watch(
  () => props.open,
  (isOpen) => {
    if (isOpen) {
      openedAt.value = Date.now()
    }
  }
)

function close() {
  emit('update:open', false)
}

function onBackdropPointerDown() {
  if (Date.now() - openedAt.value < 400) {
    return
  }
  close()
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" :class="['fixed inset-0 z-50', overlayClass]">
      <div
        class="absolute inset-0 z-0 bg-elevated/75"
        aria-hidden="true"
        @pointerdown="onBackdropPointerDown"
      />
      <aside
        :class="[
          'absolute inset-y-0 z-10 flex flex-col bg-default shadow-xl ring ring-default',
          side === 'left' ? 'left-0' : 'right-0',
          widthClass
        ]"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="labelledBy"
        @pointerdown.stop
        @click.stop
      >
        <slot :close="close" />
      </aside>
    </div>
  </Teleport>
</template>
