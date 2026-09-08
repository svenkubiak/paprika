<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '@/lib/api'
import FieldLabelHelp from '@/components/FieldLabelHelp.vue'
import { useBootstrap } from '@/composables/useBootstrap'
import { useAppToast } from '@/composables/useAppToast'
import {
  RULE_PRESETS,
  ruleLevelForSelect,
  ruleLevelFromSelect,
  ruleValueFromLevel,
  SELECT_EMPTY
} from '@/lib/utils'
import type { CollectionDefinition, FieldDefinition, RuleLevel, RulePreset } from '@/types'

type RuleSelectLevel = RuleLevel | typeof SELECT_EMPTY

const route = useRoute()
const toast = useAppToast()
const { bootstrap } = useBootstrap()

const collection = computed(() => String(route.params.collection))
const isUsers = computed(() => collection.value === 'users')
const definition = ref<CollectionDefinition | null>(null)
const loading = ref(true)
const saving = ref(false)

const form = ref({
  listRule: SELECT_EMPTY as RuleSelectLevel,
  viewRule: SELECT_EMPTY as RuleSelectLevel,
  createRule: SELECT_EMPTY as RuleSelectLevel,
  updateRule: SELECT_EMPTY as RuleSelectLevel,
  deleteRule: SELECT_EMPTY as RuleSelectLevel,
  ownerField: 'owner'
})

const ruleFields = computed(() => [
  {
    key: 'listRule' as const,
    label: 'List rule',
    hint: `GET /api/collections/${collection.value}`
  },
  {
    key: 'viewRule' as const,
    label: 'View rule',
    hint: `GET /api/collections/${collection.value}/{id}`
  },
  {
    key: 'createRule' as const,
    label: 'Create rule',
    hint: `POST /api/collections/${collection.value}`
  },
  {
    key: 'updateRule' as const,
    label: 'Update rule',
    hint: `PATCH /api/collections/${collection.value}/{id}`
  },
  {
    key: 'deleteRule' as const,
    label: 'Delete rule',
    hint: `DELETE /api/collections/${collection.value}/{id}`
  }
])

const ruleLevelChoices = [
  { label: 'No access', value: SELECT_EMPTY, icon: 'i-lucide-lock', preset: 'locked' as RulePreset },
  { label: 'Public', value: '*', icon: 'i-lucide-globe', preset: 'public' as RulePreset },
  { label: 'Signed in', value: 'auth', icon: 'i-lucide-user-check', preset: 'auth' as RulePreset },
  { label: 'Own records', value: 'owner', icon: 'i-lucide-user-cog', preset: 'owner' as RulePreset }
]

const levelOptions = ruleLevelChoices.map(({ label, value, icon }) => ({ label, value, icon }))

const presets = ruleLevelChoices.map(({ preset, label, icon }) => ({ key: preset, label, icon }))

function iconForLevel(level: RuleSelectLevel) {
  return ruleLevelChoices.find((choice) => choice.value === level)?.icon ?? 'i-lucide-shield'
}

const ownerFieldOptions = computed(() => {
  const fields = definition.value?.fields ?? []
  const relationUsers = fields.filter(isUsersRelationField)
  const options = relationUsers.map((field) => ({
    label: `${field.name} → users (${bootstrap.value?.activeTenant?.name ?? 'current tenant'})`,
    value: field.name
  }))

  if (options.length === 0) {
    return [{ label: 'owner (add a RELATION → users field in Schema)', value: 'owner' }]
  }

  return options
})

const usesOwnerRules = computed(() =>
  [form.value.listRule, form.value.viewRule, form.value.updateRule, form.value.deleteRule].some(
    (rule) => rule === 'owner'
  )
)

onMounted(loadDefinition)

function isUsersRelationField(field: FieldDefinition) {
  return field.type === 'RELATION' && field.options?.collection === 'users'
}

function defaultOwnerField(fields: FieldDefinition[]) {
  const match = fields.find(isUsersRelationField)
  return match?.name ?? 'owner'
}

async function loadDefinition() {
  loading.value = true
  try {
    definition.value = await api.getCollectionDefinition(collection.value)
    const rules = definition.value.rules
    form.value = {
      listRule: ruleLevelForSelect(rules?.listRule),
      viewRule: ruleLevelForSelect(rules?.viewRule),
      createRule: ruleLevelForSelect(rules?.createRule),
      updateRule: ruleLevelForSelect(rules?.updateRule),
      deleteRule: ruleLevelForSelect(rules?.deleteRule),
      ownerField: rules?.ownerField || defaultOwnerField(definition.value.fields)
    }
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to load rules',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    loading.value = false
  }
}

function applyPreset(preset: RulePreset) {
  const values = RULE_PRESETS[preset]
  form.value = {
    listRule: ruleLevelForSelect(values.listRule),
    viewRule: ruleLevelForSelect(values.viewRule),
    createRule: ruleLevelForSelect(values.createRule),
    updateRule: ruleLevelForSelect(values.updateRule),
    deleteRule: ruleLevelForSelect(values.deleteRule),
    ownerField:
      preset === 'owner'
        ? defaultOwnerField(definition.value?.fields ?? [])
        : form.value.ownerField
  }
}

async function saveRules() {
  if (!definition.value) return
  saving.value = true
  try {
    const next: CollectionDefinition = {
      ...definition.value,
      rules: {
        listRule: ruleValueFromLevel(ruleLevelFromSelect(form.value.listRule)),
        viewRule: ruleValueFromLevel(ruleLevelFromSelect(form.value.viewRule)),
        createRule: ruleValueFromLevel(ruleLevelFromSelect(form.value.createRule)),
        updateRule: ruleValueFromLevel(ruleLevelFromSelect(form.value.updateRule)),
        deleteRule: ruleValueFromLevel(ruleLevelFromSelect(form.value.deleteRule)),
        ownerField: form.value.ownerField
      }
    }
    await api.updateCollectionDefinition(collection.value, definition.value.id, next)
    definition.value = next
    toast.add({ title: 'Rules saved', color: 'success', icon: 'i-lucide-circle-check' })
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to save rules',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="space-y-6">
    <UAlert
      color="info"
      variant="soft"
      icon="i-lucide-shield"
      title="API access rules"
      description="Use presets to set all rules at once, or pick an access level for each operation. API clients authenticate with Authorization: Bearer …"
    />

    <UAlert
      v-if="isUsers"
      color="info"
      variant="soft"
      icon="i-lucide-user-plus"
      title="Self-registration is separate from these rules"
      description="These rules only govern the REST API for user records (/api/collections/users). Self-registration uses POST /api/auth/register and is controlled by the Self-registration toggle on the Data tab — it is not affected by the Create rule. Registered users can only read or edit their own profile once you open the View/Update rules (e.g. Own records)."
    />

    <UCard>
      <template #header>
        <div class="flex items-center gap-2">
          <UIcon name="i-lucide-sliders-horizontal" class="size-5 text-primary" />
          <h2 class="font-semibold">Presets</h2>
        </div>
      </template>
      <div class="flex flex-wrap gap-2">
        <UButton
          v-for="preset in presets"
          :key="preset.key"
          variant="soft"
          color="neutral"
          :icon="preset.icon"
          @click="applyPreset(preset.key)"
        >
          {{ preset.label }}
        </UButton>
      </div>
    </UCard>

    <UCard>
      <template #header>
        <div class="flex items-center justify-between gap-3">
          <div class="flex items-center gap-2">
            <UIcon name="i-lucide-list-checks" class="size-5 text-primary" />
            <h2 class="font-semibold">Operation rules</h2>
          </div>
          <UButton :loading="saving" icon="i-lucide-save" @click="saveRules">Save rules</UButton>
        </div>
      </template>

      <div v-if="loading" class="py-8 text-center text-muted">Loading rules…</div>
      <div v-else class="space-y-4">
        <UFormField v-if="usesOwnerRules" class="w-full">
          <template #label>
            <FieldLabelHelp
              label="Owner field"
              hint="Schema field that stores the tenant user id. Use a RELATION → users field in Schema."
            />
          </template>
          <USelect v-model="form.ownerField" :items="ownerFieldOptions" icon="i-lucide-user-cog" class="w-full" />
        </UFormField>

        <div class="grid gap-4 md:grid-cols-2">
          <UFormField v-for="field in ruleFields" :key="field.key" class="w-full">
            <template #label>
              <FieldLabelHelp :label="field.label" :hint="field.hint" />
            </template>
            <USelect
              v-model="form[field.key]"
              :items="levelOptions"
              :icon="iconForLevel(form[field.key])"
              class="w-full"
            />
          </UFormField>
        </div>
      </div>
    </UCard>

    <UCard>
      <template #header>
        <div class="flex items-center gap-2">
          <UIcon name="i-lucide-info" class="size-5 text-primary" />
          <h2 class="font-semibold">Notes</h2>
        </div>
      </template>
      <ul class="list-disc space-y-2 pl-5 text-sm text-muted">
        <li>List rules also filter which records are returned.</li>
        <li>
          Own records compares the owner field to <code>auth.id</code> from the tenant user's JWT.
        </li>
        <li>
          Add a <code>RELATION → users</code> field in Schema, select it here, then use the Own records
          preset. Paprika sets the field automatically on create.
        </li>
      </ul>
    </UCard>
  </div>
</template>
