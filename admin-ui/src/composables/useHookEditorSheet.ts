import { ref } from 'vue'
import type { HookEditorForm } from '@/components/HookEditorSheet.vue'
import { generateRandomSecret } from '@/lib/utils'

export type HookEditorMode = 'add' | 'edit'

const open = ref(false)
const editorForm = ref<HookEditorForm>(emptyHookForm())
const editorMode = ref<HookEditorMode>('add')
const editorId = ref<string | null>(null)
const saving = ref(false)

let onSave: (() => void | Promise<void>) | null = null

function cloneForm(form: HookEditorForm): HookEditorForm {
  return { ...form }
}

export function emptyHookForm(): HookEditorForm {
  return {
    name: '',
    event: 'beforeCreate',
    url: '',
    method: 'POST',
    timeoutMs: 5000,
    secret: generateRandomSecret(),
    enabled: true,
    priority: 100,
    includeSchema: false,
    failOpen: false
  }
}

export function useHookEditorSheet() {
  function openEditor(options: {
    mode: HookEditorMode
    form: HookEditorForm
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
