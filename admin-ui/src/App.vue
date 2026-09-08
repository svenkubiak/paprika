<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppLayout from '@/layouts/AppLayout.vue'
import SchemaEditorSheet from '@/components/SchemaEditorSheet.vue'
import HookEditorSheet from '@/components/HookEditorSheet.vue'
import GlobalHookEditorSheet from '@/components/GlobalHookEditorSheet.vue'
import { useSchemaEditorSheet } from '@/composables/useSchemaEditorSheet'
import { useHookEditorSheet } from '@/composables/useHookEditorSheet'
import { useGlobalHookEditorSheet } from '@/composables/useGlobalHookEditorSheet'

const route = useRoute()
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
    <RouterView v-if="isAuthLayout" />
    <RouterView v-else v-slot="{ Component }">
      <AppLayout>
        <component :is="Component" />
      </AppLayout>
    </RouterView>

    <SchemaEditorSheet
      v-if="!isAuthLayout"
      v-model:open="schemaEditorOpen"
      :row="schemaEditorRow"
      :mode="schemaEditorMode"
      :saving="schemaEditorSaving"
      @save="saveSchemaEditor"
      @delete="deleteSchemaEditorField"
    />

    <HookEditorSheet
      v-if="!isAuthLayout"
      v-model:open="hookEditorOpen"
      :form="hookEditorForm"
      :mode="hookEditorMode"
      :saving="hookEditorSaving"
      @save="saveHookEditor"
    />

    <GlobalHookEditorSheet
      v-if="!isAuthLayout"
      v-model:open="globalHookEditorOpen"
      :form="globalHookEditorForm"
      :mode="globalHookEditorMode"
      :saving="globalHookEditorSaving"
      @save="saveGlobalHookEditor"
    />
  </UApp>
</template>
