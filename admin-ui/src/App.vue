<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppLayout from '@/layouts/AppLayout.vue'
import SchemaEditorSheet from '@/components/SchemaEditorSheet.vue'
import HookEditorSheet from '@/components/HookEditorSheet.vue'
import GlobalHookEditorSheet from '@/components/GlobalHookEditorSheet.vue'
import { useSchemaEditorSheet } from '@/composables/useSchemaEditorSheet'
import { useHookEditorSheet } from '@/composables/useHookEditorSheet'
import { useGlobalHookEditorSheet } from '@/composables/useGlobalHookEditorSheet'

const route = useRoute()
const router = useRouter()

// The app is mounted before the first navigation resolves so a failure has something to render
// into. Until it has resolved there is no route to show yet - hence a placeholder rather than
// the shell of a page the user may not even end up on. A navigation that never settles shows
// this instead of a blank document.
const routerReady = ref(false)
void router.isReady().then(
  () => {
    routerReady.value = true
  },
  () => {
    routerReady.value = true
  }
)

const { open: schemaEditorOpen, editorRow: schemaEditorRow, editorMode: schemaEditorMode, saving: schemaEditorSaving, save: saveSchemaEditor, deleteField: deleteSchemaEditorField } = useSchemaEditorSheet()
const { open: hookEditorOpen, editorForm: hookEditorForm, editorMode: hookEditorMode, saving: hookEditorSaving, save: saveHookEditor } = useHookEditorSheet()
const { open: globalHookEditorOpen, editorForm: globalHookEditorForm, editorMode: globalHookEditorMode, saving: globalHookEditorSaving, save: saveGlobalHookEditor } = useGlobalHookEditorSheet()

const isAuthLayout = computed(() => route.meta.public === true)
</script>

<template>
  <UApp
    class="min-h-svh w-full"
    :toaster="{ duration: 4000, progress: false, expand: false, position: 'top-right' }"
  >
    <div v-if="!routerReady" class="flex min-h-svh items-center justify-center bg-muted/30">
      <UIcon name="i-lucide-loader-circle" class="size-6 animate-spin text-muted" />
    </div>

    <RouterView v-else-if="isAuthLayout" />
    <RouterView v-else v-slot="{ Component }">
      <AppLayout>
        <component :is="Component" />
      </AppLayout>
    </RouterView>

    <SchemaEditorSheet
      v-if="routerReady && !isAuthLayout"
      v-model:open="schemaEditorOpen"
      :row="schemaEditorRow"
      :mode="schemaEditorMode"
      :saving="schemaEditorSaving"
      @save="saveSchemaEditor"
      @delete="deleteSchemaEditorField"
    />

    <HookEditorSheet
      v-if="routerReady && !isAuthLayout"
      v-model:open="hookEditorOpen"
      :form="hookEditorForm"
      :mode="hookEditorMode"
      :saving="hookEditorSaving"
      @save="saveHookEditor"
    />

    <GlobalHookEditorSheet
      v-if="routerReady && !isAuthLayout"
      v-model:open="globalHookEditorOpen"
      :form="globalHookEditorForm"
      :mode="globalHookEditorMode"
      :saving="globalHookEditorSaving"
      @save="saveGlobalHookEditor"
    />
  </UApp>
</template>
