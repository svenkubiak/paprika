// Fixed zone, so the datetime-local conversion has a known offset (UTC+1 in January)
process.env.TZ = 'Europe/Berlin'

import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import type { FieldDefinition } from '../src/types.ts'
import {
  changeField,
  conditionIssue,
  encodeEntries,
  filterEntries,
  filterFields,
  isComplete,
  listRequestPath,
  localDateTimeToIso,
  nextCondition,
  operatorsFor,
  readableListRequestPath,
  type FilterCondition,
  type FilterField
} from '../src/lib/list-filters.ts'

const FIELDS: FieldDefinition[] = [
  { name: 'title', type: 'STRING', required: true },
  { name: 'price', type: 'NUMBER', required: false },
  { name: 'status', type: 'SELECT', required: false, options: { values: ['draft', 'published'] } },
  { name: 'featured', type: 'BOOLEAN', required: false },
  { name: 'publishedAt', type: 'DATETIME', required: false },
  { name: 'cover', type: 'FILE', required: false },
  { name: 'meta', type: 'JSON', required: false }
]
const FILTER_FIELDS = filterFields(FIELDS)

function field(name: string): FilterField {
  const found = FILTER_FIELDS.find((candidate) => candidate.name === name)
  assert.ok(found, name)
  return found
}

function condition(id: number, fieldName: string, operator: FilterCondition['operator'], value = '', values: string[] = []): FilterCondition {
  return { id, field: fieldName, operator, value, values }
}

describe('filterFields', () => {
  it('offers every schema field except JSON, plus id, createdAt and updatedAt', () => {
    assert.deepEqual(
      FILTER_FIELDS.map((candidate) => candidate.name),
      ['title', 'price', 'status', 'featured', 'publishedAt', 'cover', 'id', 'createdAt', 'updatedAt']
    )
  })

  it('treats the system fields like their stored type', () => {
    assert.equal(field('id').type, 'STRING')
    assert.equal(field('createdAt').type, 'DATETIME')
    assert.equal(field('updatedAt').type, 'DATETIME')
  })

  it('carries the allowed values of a select field', () => {
    assert.deepEqual(field('status').options, ['draft', 'published'])
    assert.deepEqual(field('title').options, [])
  })
})

describe('operatorsFor', () => {
  it('offers order comparisons only on ordered types', () => {
    for (const type of ['NUMBER', 'DATE', 'TIME', 'DATETIME'] as const) {
      assert.ok(operatorsFor(type).includes('gte'), type)
    }
    for (const type of ['STRING', 'EMAIL', 'URL', 'SELECT', 'RELATION', 'BOOLEAN', 'FILE'] as const) {
      assert.ok(!operatorsFor(type).includes('gte'), type)
    }
  })

  it('offers contains on text, select and relation, startsWith on text only', () => {
    for (const type of ['STRING', 'EMAIL', 'URL', 'SELECT', 'RELATION'] as const) {
      assert.ok(operatorsFor(type).includes('contains'), type)
    }
    assert.ok(operatorsFor('STRING').includes('startsWith'))
    assert.ok(!operatorsFor('SELECT').includes('startsWith'))
    assert.ok(!operatorsFor('NUMBER').includes('contains'))
  })

  it('offers only presence on files and nothing on JSON', () => {
    assert.deepEqual(operatorsFor('FILE'), ['null', 'notnull'])
    assert.deepEqual(operatorsFor('JSON'), [])
  })
})

describe('changeField', () => {
  it('keeps the operator when the new field supports it and clears the value', () => {
    const changed = changeField(condition(1, 'title', 'eq', 'x', ['y']), field('price'))

    assert.deepEqual(changed, condition(1, 'price', 'eq'))
  })

  it('falls back to the default operator when the new field does not support it', () => {
    assert.equal(changeField(condition(1, 'title', 'startsWith', 'x'), field('price')).operator, 'eq')
    assert.equal(changeField(condition(1, 'price', 'gte', '1'), field('cover')).operator, 'null')
  })
})

describe('nextCondition', () => {
  it('starts with the first field and its default operator', () => {
    assert.deepEqual(nextCondition(1, FILTER_FIELDS, []), condition(1, 'title', 'contains'))
  })

  it('takes the next unused operator of the field instead of repeating one', () => {
    const used = [condition(1, 'title', 'contains', 'x')]

    assert.deepEqual(nextCondition(2, FILTER_FIELDS, used), condition(2, 'title', 'eq'))
  })

  it('moves on to the next field once every operator of a field is used', () => {
    const used = operatorsFor('STRING').map((operator, index) => condition(index, 'title', operator))

    assert.deepEqual(nextCondition(99, FILTER_FIELDS, used), condition(99, 'price', 'eq'))
  })

  it('returns null when every combination is used', () => {
    const fields: FilterField[] = [{ name: 'cover', type: 'FILE', options: [] }]
    const used = [condition(1, 'cover', 'null'), condition(2, 'cover', 'notnull')]

    assert.equal(nextCondition(3, fields, used), null)
  })
})

describe('isComplete', () => {
  it('needs a value for scalar operators, an entry for lists and nothing for presence', () => {
    assert.equal(isComplete(condition(1, 'title', 'eq', '  ')), false)
    assert.equal(isComplete(condition(1, 'title', 'eq', 'x')), true)
    assert.equal(isComplete(condition(1, 'title', 'in')), false)
    assert.equal(isComplete(condition(1, 'title', 'in', '', ['x'])), true)
    assert.equal(isComplete(condition(1, 'cover', 'notnull')), true)
  })
})

describe('conditionIssue', () => {
  it('reports a repeated field and operator on the later condition only', () => {
    const conditions = [condition(1, 'price', 'gte', '1'), condition(2, 'price', 'gte', '2')]

    assert.equal(conditionIssue(conditions[0], conditions, FILTER_FIELDS), null)
    assert.equal(conditionIssue(conditions[1], conditions, FILTER_FIELDS), 'price ≥ is already used')
  })

  it('allows the same field with different operators, for a range', () => {
    const conditions = [condition(1, 'price', 'gte', '1'), condition(2, 'price', 'lt', '9')]

    assert.equal(conditionIssue(conditions[1], conditions, FILTER_FIELDS), null)
  })

  it('reports a list entry of a number field that is not a number', () => {
    const invalid = condition(1, 'price', 'in', '', ['10', 'ten'])

    assert.equal(conditionIssue(invalid, [invalid], FILTER_FIELDS), '“ten” is not a number')
  })

  it('reports a field the collection does not have', () => {
    const unknown = condition(1, 'gone', 'eq', 'x')

    assert.equal(conditionIssue(unknown, [unknown], FILTER_FIELDS), 'Unknown field')
  })
})

describe('filterEntries', () => {
  it('turns conditions into filter[field][operator] parameters in builder order', () => {
    const entries = filterEntries([
      condition(1, 'title', 'contains', ' paprika '),
      condition(2, 'price', 'gte', '10'),
      condition(3, 'cover', 'notnull'),
      condition(4, 'featured', 'eq', 'true')
    ], FILTER_FIELDS)

    assert.deepEqual(entries, [
      ['filter[title][contains]', 'paprika'],
      ['filter[price][gte]', '10'],
      ['filter[cover][notnull]', ''],
      ['filter[featured][eq]', 'true']
    ])
  })

  it('sends lists as JSON arrays that keep the field type', () => {
    const entries = filterEntries([
      condition(1, 'price', 'in', '', ['10', '2.5']),
      condition(2, 'status', 'nin', '', ['draft']),
      condition(3, 'title', 'in', '', ['a,b', 'c"d'])
    ], FILTER_FIELDS)

    assert.deepEqual(entries, [
      ['filter[price][in]', '[10,2.5]'],
      ['filter[status][nin]', '["draft"]'],
      ['filter[title][in]', '["a,b","c\\"d"]']
    ])
  })

  it('sends date & time in UTC', () => {
    const entries = filterEntries([condition(1, 'createdAt', 'gte', '2026-01-01T10:00:00')], FILTER_FIELDS)

    assert.deepEqual(entries, [['filter[createdAt][gte]', '2026-01-01T09:00:00.000Z']])
  })

  it('leaves out incomplete conditions and conditions with an issue', () => {
    const entries = filterEntries([
      condition(1, 'title', 'eq', ''),
      condition(2, 'price', 'in', '', ['x']),
      condition(3, 'price', 'gte', '1'),
      condition(4, 'price', 'gte', '2')
    ], FILTER_FIELDS)

    assert.deepEqual(entries, [['filter[price][gte]', '1']])
  })
})

describe('localDateTimeToIso', () => {
  it('converts the local time of a datetime-local input to UTC', () => {
    assert.equal(localDateTimeToIso('2026-07-01T10:00'), '2026-07-01T08:00:00.000Z')
    assert.equal(localDateTimeToIso('2026-01-01T10:00:30'), '2026-01-01T09:00:30.000Z')
  })

  it('keeps a value that already has an offset or is not a date', () => {
    assert.equal(localDateTimeToIso('2026-01-01T10:00:00+02:00'), '2026-01-01T10:00:00+02:00')
    assert.equal(localDateTimeToIso('2026-01-01T10:00:00Z'), '2026-01-01T10:00:00Z')
    assert.equal(localDateTimeToIso('soon'), 'soon')
  })
})

describe('request paths', () => {
  const query = {
    collection: 'posts',
    sort: 'createdAt:desc',
    search: ' a&b ',
    filters: [
      ['filter[publishedAt][gte]', '2026-01-01T00:00:00+02:00'],
      ['filter[status][in]', '["draft"]']
    ] as [string, string][]
  }

  it('encodes every part for the request and the copied URL', () => {
    assert.equal(encodeEntries(query.filters),
      '&filter%5BpublishedAt%5D%5Bgte%5D=2026-01-01T00%3A00%3A00%2B02%3A00&filter%5Bstatus%5D%5Bin%5D=%5B%22draft%22%5D')
    assert.equal(listRequestPath(query),
      '/api/collections/posts?sort=createdAt%3Adesc&search=a%26b' +
      '&filter%5BpublishedAt%5D%5Bgte%5D=2026-01-01T00%3A00%3A00%2B02%3A00&filter%5Bstatus%5D%5Bin%5D=%5B%22draft%22%5D')
  })

  it('shows a readable path that keeps + and & unambiguous', () => {
    assert.equal(readableListRequestPath(query),
      '/api/collections/posts?sort=createdAt:desc&search=a%26b' +
      '&filter[publishedAt][gte]=2026-01-01T00:00:00%2B02:00&filter[status][in]=["draft"]')
  })

  it('has no filter part without conditions', () => {
    assert.equal(encodeEntries([]), '')
    assert.equal(listRequestPath({ collection: 'posts', sort: 'id:asc', search: '', filters: [] }),
      '/api/collections/posts?sort=id%3Aasc')
  })
})
