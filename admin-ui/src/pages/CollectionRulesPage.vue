<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouteCollection } from '@/composables/useRouteCollection'
import { api } from '@/lib/api'
import PField from '@/components/PField.vue'
import { useBootstrap } from '@/composables/useBootstrap'
import { useAppToast } from '@/composables/useAppToast'
import {
  isMembershipLevel,
  RULE_PRESETS,
  ruleLevelForSelect,
  ruleLevelFromSelect,
  ruleValueFromLevel
} from '@/lib/utils'
import {
  RULE_OPERATIONS,
  ruleLevelChoices,
  summarizeRules,
  type RuleKey,
  type RuleSelectLevel
} from '@/lib/rule-levels'
import type { CollectionDefinition, FieldDefinition, IndexDefinition, RulePreset } from '@/types'

type RulesForm = Record<RuleKey, RuleSelectLevel> & {
  ownerField: string
  groupCollection: string
  groupMemberField: string
  groupField: string
  groupRecordField: string
}

const toast = useAppToast()
const { bootstrap } = useBootstrap()

const collection = useRouteCollection()
const isUsers = computed(() => collection.value === 'users')
const definition = ref<CollectionDefinition | null>(null)
const saving = ref(false)

const form = ref<RulesForm>(formFromDefinition(null))
/** The rules as stored; the save bar shows while the form differs from it. */
const savedForm = ref<RulesForm>(formFromDefinition(null))
const dirty = computed(() => JSON.stringify(form.value) !== JSON.stringify(savedForm.value))

const membershipFields = ref<FieldDefinition[]>([])
const membershipIndexes = ref<IndexDefinition[]>([])

/** Kept out of the template because the sentence quotes the literal field 'id'. */
const groupRecordFieldDetails =
  "Choose 'id' if the records of this collection are the groups themselves - then create needs " +
  'another level, because nobody can be a member of a group that does not exist yet.'

const choices = computed(() => ruleLevelChoices(isUsers.value))
const levelOptions = computed(() =>
  choices.value.map(({ label, value, icon, description }) => ({ label, value, icon, description }))
)
const presetOptions = computed(() =>
  choices.value.map(({ label, preset, icon }) => ({ label, value: preset, icon }))
)

const operations = computed(() =>
  RULE_OPERATIONS.map((op) => ({
    ...op,
    endpoint: `${op.method} /api/collections/${collection.value}${op.path}`
  }))
)

const summary = computed(() => summarizeRules(form.value, isUsers.value))

function iconForLevel(level: RuleSelectLevel) {
  return choices.value.find((choice) => choice.value === level)?.icon ?? 'i-lucide-shield'
}

const ownerFieldOptions = computed(() => {
  const fields = definition.value?.fields ?? []
  const options = fields.filter(isUsersRelationField).map((field) => ({
    label: `${field.name} → users (${bootstrap.value?.activeTenant?.name ?? 'current tenant'})`,
    value: field.name
  }))
  if (options.length === 0) {
    return [{ label: 'owner (add a RELATION → users field in Schema)', value: 'owner' }]
  }
  return options
})

// On users, Own records means the caller's own account, so no owner field is involved.
const usesOwnerRules = computed(
  () =>
    !isUsers.value &&
    [form.value.listRule, form.value.viewRule, form.value.updateRule, form.value.deleteRule].some(
      (rule) => rule === 'owner'
    )
)

const allLevels = computed(() => RULE_OPERATIONS.map((op) => form.value[op.key]))
const usesMembershipRules = computed(() => allLevels.value.some(isMembershipLevel))
const usesGroupRules = computed(() => allLevels.value.some((level) => level === 'group'))
const usesPeersRules = computed(() => allLevels.value.some((level) => level === 'peers'))

// The lookup filters on the member field on every request, and on the group field to find the
// peers. MongoDB serves a filter only from an index that starts with the field, so a compound
// index counts for its leading field alone.
const unindexedMembershipFields = computed(() => {
  if (!form.value.groupCollection) return []
  const leading = new Set(membershipIndexes.value.map((index) => index.fields[0]?.field))
  return [form.value.groupMemberField, usesPeersRules.value ? form.value.groupField : '']
    .filter((field) => field && !leading.has(field))
})

// The own collection stays in the list: a self-scoping membership collection is how a group's
// member list is shown. Not circular - the resolver queries the memberships once.
const collectionOptions = computed(() =>
  (bootstrap.value?.collections ?? []).map((name) => ({
    label: name === collection.value ? `${name} (this collection — the memberships themselves)` : name,
    value: name
  }))
)

/** A membership is matched by comparing ids, so only RELATION and STRING fields can carry one. */
function lookupFieldOptions(fields: FieldDefinition[]) {
  return fields
    .filter((field) => field.type === 'RELATION' || field.type === 'STRING')
    .map((field) => ({ label: field.name, value: field.name }))
}

const membershipFieldOptions = computed(() => lookupFieldOptions(membershipFields.value))
// "id": the records of this collection are themselves the groups.
const recordFieldOptions = computed(() => [
  { label: 'id (the record is the group)', value: 'id' },
  ...lookupFieldOptions(definition.value?.fields ?? [])
])

// The membership collection's schema needs its own request, unless it is this collection.
watch(
  () => form.value.groupCollection,
  async (name) => {
    if (!name) {
      membershipFields.value = []
      membershipIndexes.value = []
      return
    }
    if (name === collection.value && definition.value) {
      membershipFields.value = definition.value.fields ?? []
      membershipIndexes.value = definition.value.indexes ?? []
      return
    }
    try {
      const membership = await api.getCollectionDefinition(name)
      membershipFields.value = membership.fields ?? []
      membershipIndexes.value = membership.indexes ?? []
    } catch {
      membershipFields.value = []
      membershipIndexes.value = []
    }
  }
)

// Also reload on a collection change: navigating between collections reuses this component.
watch(collection, loadDefinition)

function isUsersRelationField(field: FieldDefinition) {
  return field.type === 'RELATION' && field.options?.collection === 'users'
}

function defaultOwnerField(fields: FieldDefinition[]) {
  const match = fields.find(isUsersRelationField)
  return match?.name ?? 'owner'
}

function formFromDefinition(source: CollectionDefinition | null): RulesForm {
  const rules = source?.rules
  return {
    listRule: ruleLevelForSelect(rules?.listRule),
    viewRule: ruleLevelForSelect(rules?.viewRule),
    createRule: ruleLevelForSelect(rules?.createRule),
    updateRule: ruleLevelForSelect(rules?.updateRule),
    deleteRule: ruleLevelForSelect(rules?.deleteRule),
    ownerField: rules?.ownerField || defaultOwnerField(source?.fields ?? []),
    groupCollection: rules?.groupCollection ?? '',
    groupMemberField: rules?.groupMemberField ?? '',
    groupField: rules?.groupField ?? '',
    groupRecordField: rules?.groupRecordField ?? ''
  }
}

async function loadDefinition() {
  try {
    definition.value = await api.getCollectionDefinition(collection.value)
    form.value = formFromDefinition(definition.value)
    savedForm.value = { ...form.value }
  } catch (error) {
    toast.add({
      title: error instanceof Error ? error.message : 'Failed to load rules',
      color: 'error',
      icon: 'i-lucide-circle-x'
    })
  }
}

// Fills all five operations at once; like any other edit it only takes effect on save.
function applyPreset(preset: RulePreset | undefined) {
  if (!preset) return
  const values = RULE_PRESETS[preset]
  form.value = {
    ...form.value,
    listRule: ruleLevelForSelect(values.listRule),
    viewRule: ruleLevelForSelect(values.viewRule),
    createRule: ruleLevelForSelect(values.createRule),
    updateRule: ruleLevelForSelect(values.updateRule),
    deleteRule: ruleLevelForSelect(values.deleteRule),
    ownerField:
      preset === 'owner' ? defaultOwnerField(definition.value?.fields ?? []) : form.value.ownerField
  }
}

function discardChanges() {
  form.value = { ...savedForm.value }
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
        ownerField: form.value.ownerField,
        groupCollection: form.value.groupCollection || null,
        groupMemberField: form.value.groupMemberField || null,
        groupField: form.value.groupField || null,
        groupRecordField: form.value.groupRecordField || null
      }
    }
    await api.updateCollectionDefinition(collection.value, definition.value.id, next)
    definition.value = next
    savedForm.value = { ...form.value }
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

// Awaited in setup, so the <Suspense> in App.vue keeps the previous tab on screen until this one
// has its data instead of flashing the empty state first.
await loadDefinition()
</script>

<template>
  <div class="space-y-6">
    <UCard :ui="{ body: 'p-0 sm:p-0' }">
      <template #header>
        <div class="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 class="font-semibold">Who can do what</h2>
            <p class="mt-1 text-sm text-muted">
              Access to this collection through the API. Superadmins always have full access.
            </p>
          </div>
          <USelect
            :model-value="undefined"
            :items="presetOptions"
            placeholder="Set all to…"
            icon="i-lucide-sliders-horizontal"
            class="w-full sm:w-48"
            @update:model-value="applyPreset($event as RulePreset)"
          />
        </div>
      </template>

      <table class="w-full text-sm">
        <tbody class="divide-y divide-default">
          <tr v-for="op in operations" :key="op.key">
            <td class="px-4 py-3 align-top sm:px-6">
              <div class="font-medium text-default">{{ op.label }}</div>
              <code class="text-xs text-muted">{{ op.endpoint }}</code>
            </td>
            <td class="w-full px-4 py-3 align-top sm:w-72 sm:pr-6">
              <USelect
                v-model="form[op.key]"
                :items="levelOptions"
                :icon="iconForLevel(form[op.key])"
                :aria-label="`${op.label} rule`"
                class="w-full"
              />
            </td>
          </tr>
        </tbody>
      </table>

      <div class="space-y-1 border-t border-default bg-muted/15 px-4 py-3 text-sm sm:px-6">
        <p v-for="sentence in summary" :key="sentence" class="text-toned">{{ sentence }}</p>
      </div>
    </UCard>

    <UCard v-if="usesOwnerRules">
      <template #header>
        <h2 class="font-semibold">Owner</h2>
        <p class="mt-1 text-sm text-muted">
          Own records compares this field with the signed-in user. Paprika fills it in on create.
        </p>
      </template>
      <PField
        label="Owner field"
        icon="i-lucide-user-cog"
        orientation="horizontal"
        help="A RELATION → users field, added on the Schema tab."
      >
        <USelect v-model="form.ownerField" :items="ownerFieldOptions" icon="i-lucide-user-cog" />
      </PField>
    </UCard>

    <UCard v-if="usesMembershipRules">
      <template #header>
        <h2 class="font-semibold">Groups</h2>
        <p class="mt-1 text-sm text-muted">
          <template v-if="isUsers">
            Group peers lets users reach the users they share a group with, plus their own account.
          </template>
          <template v-else>
            Group members compares the record's group with the groups of the signed-in user. Without
            a membership a user sees nothing, and cannot put a record into a group they are not in.
          </template>
        </p>
      </template>

      <div class="space-y-4">
        <UAlert
          v-if="unindexedMembershipFields.length > 0"
          color="warning"
          variant="soft"
          icon="i-lucide-gauge"
          title="Index the membership collection"
          :description="`Every request resolves the caller's groups by querying ${form.groupCollection}. Add an index on ${unindexedMembershipFields.join(' and one on ')} in that collection's Schema tab - a compound index counts only for its first field. Otherwise each call costs a full collection scan.`"
        />

        <div class="grid gap-4 md:grid-cols-2">
          <PField
            label="Membership collection"
            icon="i-lucide-users"
            help="Holds one record per membership, e.g. team_members."
            details="Pick this collection itself to let the members of a group see their group's membership records - the member list."
          >
            <USelect v-model="form.groupCollection" :items="collectionOptions" icon="i-lucide-users" />
          </PField>

          <PField
            label="Member field"
            icon="i-lucide-user"
            help="Field of the membership collection pointing at the user."
            details="A RELATION → users field or a STRING holding the user id."
          >
            <USelect v-model="form.groupMemberField" :items="membershipFieldOptions" icon="i-lucide-user" />
          </PField>

          <PField
            label="Group field"
            icon="i-lucide-users-round"
            help="Field of the membership collection pointing at the group."
            details="A RELATION or a STRING holding the group id."
          >
            <USelect v-model="form.groupField" :items="membershipFieldOptions" icon="i-lucide-users-round" />
          </PField>

          <PField
            v-if="usesGroupRules"
            label="Group field on this collection"
            icon="i-lucide-folder-tree"
            help="Field of this collection carrying the group. Clients set it on create; Paprika never fills it in."
            :details="groupRecordFieldDetails"
          >
            <USelect v-model="form.groupRecordField" :items="recordFieldOptions" icon="i-lucide-folder-tree" />
          </PField>
        </div>
      </div>
    </UCard>

    <p v-if="isUsers" class="text-sm text-muted">
      Sign-up goes through <code>POST /api/auth/register</code> and is switched on under Auth; the
      Create rule does not affect it.
    </p>

    <UnsavedChangesBar
      v-if="dirty"
      :saving="saving"
      save-label="Save rules"
      @save="saveRules"
      @discard="discardChanges"
    />
  </div>
</template>
