<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '@/lib/api'
import PField from '@/components/PField.vue'
import { useBootstrap } from '@/composables/useBootstrap'
import { useAppToast } from '@/composables/useAppToast'
import {
  isMembershipLevel,
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
  ownerField: 'owner',
  groupCollection: '',
  groupMemberField: '',
  groupField: '',
  groupRecordField: ''
})

const membershipFields = ref<FieldDefinition[]>([])

/** Kept out of the template because the sentence quotes the literal field 'id'. */
const groupRecordFieldDetails =
  "Choose 'id' if the records of this collection are the groups themselves - then create needs " +
  'another preset, because nobody can be a member of a group that does not exist yet.'

const ruleFields = computed(() => [
  {
    key: 'listRule' as const,
    label: 'List rule',
    icon: 'i-lucide-list',
    hint: `GET /api/collections/${collection.value}`
  },
  {
    key: 'viewRule' as const,
    label: 'View rule',
    icon: 'i-lucide-eye',
    hint: `GET /api/collections/${collection.value}/{id}`
  },
  {
    key: 'createRule' as const,
    label: 'Create rule',
    icon: 'i-lucide-plus',
    hint: `POST /api/collections/${collection.value}`
  },
  {
    key: 'updateRule' as const,
    label: 'Update rule',
    icon: 'i-lucide-pencil',
    hint: `PATCH /api/collections/${collection.value}/{id}`
  },
  {
    key: 'deleteRule' as const,
    label: 'Delete rule',
    icon: 'i-lucide-trash-2',
    hint: `DELETE /api/collections/${collection.value}/{id}`
  }
])

const ruleLevelChoices = [
  { label: 'No access', value: SELECT_EMPTY, icon: 'i-lucide-lock', preset: 'locked' as RulePreset },
  { label: 'Public', value: '*', icon: 'i-lucide-globe', preset: 'public' as RulePreset },
  { label: 'Signed in', value: 'auth', icon: 'i-lucide-user-check', preset: 'auth' as RulePreset },
  { label: 'Own records', value: 'owner', icon: 'i-lucide-user-cog', preset: 'owner' as RulePreset },
  // "Group members" needs a group field, which user records lack; "Group peers" only makes sense
  // where the records are the users. The backend rejects the other combination on save.
  { label: 'Group members', value: 'group', icon: 'i-lucide-users', preset: 'group' as RulePreset },
  { label: 'Group peers', value: 'peers', icon: 'i-lucide-users-round', preset: 'peers' as RulePreset }
]

const availableChoices = computed(() =>
  ruleLevelChoices.filter((choice) =>
    choice.value === 'peers' ? isUsers.value : choice.value === 'group' ? !isUsers.value : true
  )
)

const levelOptions = computed(() =>
  availableChoices.value.map(({ label, value, icon }) => ({ label, value, icon }))
)

const presets = computed(() =>
  availableChoices.value.map(({ preset, label, icon }) => ({ key: preset, label, icon }))
)

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

const allLevels = computed(() => [
  form.value.listRule,
  form.value.viewRule,
  form.value.createRule,
  form.value.updateRule,
  form.value.deleteRule
])

const usesMembershipRules = computed(() => allLevels.value.some(isMembershipLevel))
const usesGroupRules = computed(() => allLevels.value.some((level) => level === 'group'))

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
      return
    }
    if (name === collection.value && definition.value) {
      membershipFields.value = definition.value.fields ?? []
      return
    }
    try {
      membershipFields.value = (await api.getCollectionDefinition(name)).fields ?? []
    } catch {
      membershipFields.value = []
    }
  }
)

// Also reload on a collection change: navigating between collections reuses this component.
onMounted(loadDefinition)
watch(collection, loadDefinition)

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
      ownerField: rules?.ownerField || defaultOwnerField(definition.value.fields),
      groupCollection: rules?.groupCollection ?? '',
      groupMemberField: rules?.groupMemberField ?? '',
      groupField: rules?.groupField ?? '',
      groupRecordField: rules?.groupRecordField ?? ''
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
        : form.value.ownerField,
    groupCollection: form.value.groupCollection,
    groupMemberField: form.value.groupMemberField,
    groupField: form.value.groupField,
    groupRecordField: form.value.groupRecordField
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
        ownerField: form.value.ownerField,
        groupCollection: form.value.groupCollection || null,
        groupMemberField: form.value.groupMemberField || null,
        groupField: form.value.groupField || null,
        groupRecordField: form.value.groupRecordField || null
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
      description="These rules only govern the REST API for user records (/api/collections/users). Self-registration uses POST /api/auth/register and is controlled by the Self-registration toggle on the Data tab — it is not affected by the Create rule. Set the View/Update rules to Own records to let each user read and edit exactly their own profile."
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
        <UAlert
          v-if="isUsers && usesOwnerRules"
          color="primary"
          variant="soft"
          icon="i-lucide-user-cog"
          title="Own records means the user's own account here"
          description="On the users collection, Own records resolves to record.id = auth.id: each user reaches exactly their own record. No RELATION → users field and no owner field are needed - a stored owner field is ignored. Create is never granted by Own records; sign-up goes through POST /api/auth/register."
        />

        <PField
          v-if="usesOwnerRules && !isUsers"
          label="Owner field"
          icon="i-lucide-user-cog"
          orientation="horizontal"
          help="Schema field that stores the tenant user id."
          details="Use a RELATION → users field, added on the Schema tab."
        >
          <USelect v-model="form.ownerField" :items="ownerFieldOptions" icon="i-lucide-user-cog" />
        </PField>

        <template v-if="usesMembershipRules">
          <UAlert
            color="warning"
            variant="soft"
            icon="i-lucide-gauge"
            title="Index the membership collection"
            :description="`Every request resolves the caller's groups by querying ${form.groupCollection || 'the membership collection'}. Add an index on the member field and one on the group field in that collection's Schema tab, otherwise each call costs a full collection scan.`"
          />

          <div class="grid gap-4 md:grid-cols-2">
            <PField
              label="Membership collection"
              icon="i-lucide-users"
              help="Holds one record per membership, e.g. team_members."
              details="Pick this collection itself to let the members of a group see their group's membership records - the member list."
            >
              <USelect
                v-model="form.groupCollection"
                :items="collectionOptions"
                icon="i-lucide-users"
              />
            </PField>

            <PField
              label="Member field"
              icon="i-lucide-user"
              help="Field of the membership collection pointing at the user."
              details="A RELATION → users field or a STRING holding the user id."
            >
              <USelect
                v-model="form.groupMemberField"
                :items="membershipFieldOptions"
                icon="i-lucide-user"
              />
            </PField>

            <PField
              label="Group field"
              icon="i-lucide-users-round"
              help="Field of the membership collection pointing at the group."
              details="A RELATION or a STRING holding the group id."
            >
              <USelect
                v-model="form.groupField"
                :items="membershipFieldOptions"
                icon="i-lucide-users-round"
              />
            </PField>

            <PField
              v-if="usesGroupRules"
              label="Group field on this collection"
              icon="i-lucide-folder-tree"
              help="Field of this collection carrying the group. Clients set it on create; Paprika never fills it in."
              :details="groupRecordFieldDetails"
            >
              <USelect
                v-model="form.groupRecordField"
                :items="recordFieldOptions"
                icon="i-lucide-folder-tree"
              />
            </PField>
          </div>
        </template>

        <div class="grid gap-4 md:grid-cols-2">
          <PField
            v-for="field in ruleFields"
            :key="field.key"
            :label="field.label"
            :icon="field.icon"
            :help="field.hint"
          >
            <USelect
              v-model="form[field.key]"
              :items="levelOptions"
              :icon="iconForLevel(form[field.key])"
            />
          </PField>
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
        <li v-if="isUsers">
          Group peers returns the users that share at least one group with the caller, plus the
          caller's own record — which stays reachable even without any membership. Create is never
          granted by it.
        </li>
        <li v-else>
          Group members compares the record's group field against the groups the caller is a member
          of. A caller without any membership sees nothing, and a record can neither be created in
          nor moved into a group the caller does not belong to.
        </li>
        <li>
          Own records compares the owner field to <code>auth.id</code> from the tenant user's JWT.
        </li>
        <li v-if="isUsers">
          On this collection, Own records compares <code>record.id</code> to <code>auth.id</code>
          instead — the own record of a user is their own account. No owner field is involved, and
          Own records never grants Create here.
        </li>
        <li v-else>
          Add a <code>RELATION → users</code> field in Schema, select it here, then use the Own records
          preset. Paprika sets the field automatically on create.
        </li>
      </ul>
    </UCard>
  </div>
</template>
