import { ruleLevelForSelect, SELECT_EMPTY } from '@/lib/utils'
import type { RuleLevel, RulePreset } from '@/types'

export type RuleSelectLevel = RuleLevel | typeof SELECT_EMPTY
export type RuleKey = 'listRule' | 'viewRule' | 'createRule' | 'updateRule' | 'deleteRule'

export interface RuleLevelChoice {
  label: string
  value: RuleSelectLevel
  icon: string
  preset: RulePreset
  /** One line under the option, so the choice explains itself. */
  description: string
  /** Who the level lets in, as the subject of the summary sentence. */
  subject: string
}

/**
 * "Group members" needs a group field, which user records lack; "Group peers" only makes sense
 * where the records are the users. The backend rejects the other combination on save.
 */
export function ruleLevelChoices(isUsers: boolean): RuleLevelChoice[] {
  const choices: RuleLevelChoice[] = [
    {
      label: 'No access',
      value: SELECT_EMPTY,
      icon: 'i-lucide-lock',
      preset: 'locked',
      description: 'Nobody through the API. Superadmins still can.',
      subject: 'Nobody'
    },
    {
      label: 'Public',
      value: '*',
      icon: 'i-lucide-globe',
      preset: 'public',
      description: 'Anyone, without signing in.',
      subject: 'Anyone'
    },
    {
      label: 'Signed in',
      value: 'auth',
      icon: 'i-lucide-user-check',
      preset: 'auth',
      description: 'Every signed-in user of this tenant.',
      subject: 'Signed-in users'
    },
    {
      label: 'Own records',
      value: 'owner',
      icon: 'i-lucide-user-cog',
      preset: 'owner',
      description: isUsers
        ? 'Each user, for their own account only.'
        : 'Only the user the record belongs to.',
      subject: isUsers ? 'Each user for their own account' : 'Only the owner'
    }
  ]
  choices.push(
    isUsers
      ? {
          label: 'Group peers',
          value: 'peers',
          icon: 'i-lucide-users-round',
          preset: 'peers',
          description: 'Users who share a group with the caller.',
          subject: 'Users sharing a group'
        }
      : {
          label: 'Group members',
          value: 'group',
          icon: 'i-lucide-users',
          preset: 'group',
          description: "Members of the record's group.",
          subject: "Members of the record's group"
        }
  )
  return choices
}

/** The choice a stored rule maps to; an unknown rule reads as no access, like on the server. */
export function ruleLevelChoice(rule: string | null | undefined, isUsers: boolean): RuleLevelChoice {
  const level = ruleLevelForSelect(rule)
  const choices = ruleLevelChoices(isUsers)
  return choices.find((choice) => choice.value === level) ?? choices[0]
}

export const RULE_OPERATIONS: { key: RuleKey; label: string; verb: string; method: string; path: string }[] = [
  { key: 'listRule', label: 'List', verb: 'list', method: 'GET', path: '' },
  { key: 'viewRule', label: 'View', verb: 'view', method: 'GET', path: '/{id}' },
  { key: 'createRule', label: 'Create', verb: 'create', method: 'POST', path: '' },
  { key: 'updateRule', label: 'Update', verb: 'update', method: 'PATCH', path: '/{id}' },
  { key: 'deleteRule', label: 'Delete', verb: 'delete', method: 'DELETE', path: '/{id}' }
]

function joinVerbs(verbs: string[]): string {
  return verbs.length <= 1 ? verbs.join('') : `${verbs.slice(0, -1).join(', ')} and ${verbs.at(-1)}`
}

/**
 * The rules in one plain sentence per access level, e.g. "Anyone can list and view. Only the owner
 * can update and delete." - the quickest way to check that the table says what was meant.
 */
export function summarizeRules(
  levels: Record<RuleKey, RuleSelectLevel>,
  isUsers: boolean
): string[] {
  return ruleLevelChoices(isUsers)
    .map((choice) => ({
      choice,
      verbs: RULE_OPERATIONS.filter((op) => levels[op.key] === choice.value).map((op) => op.verb)
    }))
    .filter(({ verbs }) => verbs.length > 0)
    .map(({ choice, verbs }) => `${choice.subject} can ${joinVerbs(verbs)}.`)
}
