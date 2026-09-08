import { ref } from 'vue'
import type { SchemaRow } from '@/components/SchemaEditorSheet.vue'

export type SchemaEditorMode = 'add' | 'edit'

const open = ref(false)
const editorRow = ref<SchemaRow>(emptyRow())
const editorMode = ref<SchemaEditorMode>('add')
const editorIndex = ref<number | null>(null)
const saving = ref(false)

let onSave: (() => void | Promise<void>) | null = null
let onDelete: (() => void | Promise<void>) | null = null

function cloneRow(row: SchemaRow): SchemaRow {
  return { ...row }
}

export function emptyRow(): SchemaRow {
  return {
    name: '',
    type: 'STRING',
    required: false,
    persisted: false,
    indexEnabled: false,
    indexDirection: 'ASC',
    indexUnique: false,
    defaultValue: '',
    relationCollection: '',
    relationMaxSelect: 1,
    relationCascadeDelete: false,
    fileMaxSize: 5 * 1024 * 1024,
    fileMimeTypes: '',
    fileMaxSelect: 1,
    selectValues: '',
    selectMaxSelect: 1,
    minLength: undefined,
    maxLength: undefined,
    pattern: '',
    numberMin: undefined,
    numberMax: undefined,
    jsonMaxBytes: undefined,
    jsonMaxDepth: undefined,
    jsonOnlyObject: false,
    jsonOnlyArray: false,
    minDate: '',
    maxDate: '',
    minTime: '',
    maxTime: '',
    minDateTime: '',
    maxDateTime: ''
  }
}

export function useSchemaEditorSheet() {
  function openEditor(options: {
    mode: SchemaEditorMode
    row: SchemaRow
    index: number | null
    save: () => void | Promise<void>
    delete?: () => void | Promise<void>
  }) {
    editorMode.value = options.mode
    editorRow.value = cloneRow(options.row)
    editorIndex.value = options.index
    onSave = options.save
    onDelete = options.delete ?? null
    open.value = true
  }

  function closeEditor() {
    open.value = false
  }

  async function save() {
    if (onSave) await onSave()
  }

  async function deleteField() {
    if (onDelete) await onDelete()
  }

  function setSaving(value: boolean) {
    saving.value = value
  }

  return {
    open,
    editorRow,
    editorMode,
    editorIndex,
    saving,
    openEditor,
    closeEditor,
    save,
    deleteField,
    setSaving
  }
}
