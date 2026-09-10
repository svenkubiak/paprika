import { ref } from 'vue'
import type { GlobalHookEditorForm } from '@/components/GlobalHookEditorSheet.vue'
import { generateRandomSecret } from '@/lib/utils'

export type GlobalHookEditorMode = 'add' | 'edit'

const open = ref(false)
const editorForm = ref<GlobalHookEditorForm>(emptyGlobalHookForm())
const editorMode = ref<GlobalHookEditorMode>('add')
const editorId = ref<string | null>(null)
const saving = ref(false)

let onSave: (() => void | Promise<void>) | null = null

function cloneForm(form: GlobalHookEditorForm): GlobalHookEditorForm {
  return {
    ...form,
    targetCollections: [...form.targetCollections]
  }
}

export function emptyGlobalHookForm(): GlobalHookEditorForm {
  return {
    name: '',
    description: '',
    url: '',
    method: 'POST',
    timeoutMs: 5000,
    secret: generateRandomSecret(),
    enabled: true,
    priority: 100,
    includeSchema: false,
    failOpen: false,
    applyToAllCollections: true,
    targetCollections: []
  }
}

export function useGlobalHookEditorSheet() {
  function openEditor(options: {
    mode: GlobalHookEditorMode
    form: GlobalHookEditorForm
    id: string | null
    save: () => void | Promise<void>
  }) {
    editorMode.value = options.mode
    editorForm.value = cloneForm(options.form)
    editorId.value = options.id
    onSave = options.save
    open.value = true
  }

  function closeEditor() {
    open.value = false
  }

  async function save() {
    if (onSave) await onSave()
  }

  function setSaving(value: boolean) {
    saving.value = value
  }

  return {
    open,
    editorForm,
    editorMode,
    editorId,
    saving,
    openEditor,
    closeEditor,
    save,
    setSaving
  }
}
