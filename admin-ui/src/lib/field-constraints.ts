import type { FieldDefinition } from '@/types'
import { formatByteSize } from '@/lib/utils'

function options(field: FieldDefinition) {
  return field.options || {}
}

export function fieldConstraintHint(field: FieldDefinition): string | undefined {
  const opts = options(field)
  const parts: string[] = []

  switch (field.type) {
    case 'STRING':
    case 'EMAIL':
    case 'URL': {
      if (opts.minLength != null && opts.maxLength != null) {
        parts.push(`${opts.minLength}-${opts.maxLength} characters`)
      } else if (opts.minLength != null) {
        parts.push(`At least ${opts.minLength} characters`)
      } else if (opts.maxLength != null) {
        parts.push(`At most ${opts.maxLength} characters`)
      }
      if (opts.pattern) parts.push(`Matches ${opts.pattern}`)
      break
    }
    case 'NUMBER': {
      if (opts.numberMin != null && opts.numberMax != null) {
        parts.push(`${opts.numberMin} to ${opts.numberMax}`)
      } else if (opts.numberMin != null) {
        parts.push(`${opts.numberMin} or more`)
      } else if (opts.numberMax != null) {
        parts.push(`${opts.numberMax} or less`)
      }
      break
    }
    case 'DATE': {
      if (opts.minDate) parts.push(`From ${opts.minDate}`)
      if (opts.maxDate) parts.push(`Until ${opts.maxDate}`)
      break
    }
    case 'TIME': {
      if (opts.minTime) parts.push(`From ${opts.minTime}`)
      if (opts.maxTime) parts.push(`Until ${opts.maxTime}`)
      break
    }
    case 'DATETIME': {
      parts.push('ISO 8601 with timezone')
      if (opts.minDateTime) parts.push(`from ${opts.minDateTime}`)
      if (opts.maxDateTime) parts.push(`until ${opts.maxDateTime}`)
      break
    }
    case 'SELECT': {
      const maxSelect = opts.maxSelect || 1
      if (maxSelect > 1) parts.push(`Up to ${maxSelect} values`)
      break
    }
    case 'RELATION': {
      const maxSelect = opts.maxSelect || 1
      if (opts.collection) parts.push(`Record id in ${opts.collection}`)
      if (maxSelect > 1) parts.push(`up to ${maxSelect} of them, comma separated`)
      if (opts.cascadeDelete) parts.push('deleted along with this record')
      break
    }
    case 'FILE': {
      const maxSelect = opts.maxSelect || 1
      const mimeTypes = opts.mimeTypes || []
      parts.push(mimeTypes.length > 0 ? mimeTypes.join(', ') : 'Any file type')
      if (opts.maxSize) parts.push(`up to ${formatByteSize(opts.maxSize)} each`)
      if (maxSelect > 1) parts.push(`${maxSelect} files`)
      break
    }
    case 'JSON': {
      if (opts.onlyObject) parts.push('Objects only')
      if (opts.onlyArray) parts.push('Arrays only')
      if (opts.maxDepth != null) parts.push(`max. depth ${opts.maxDepth}`)
      break
    }
    case 'BOOLEAN':
      break
  }

  if (parts.length === 0) return undefined
  const [first, ...rest] = parts
  return [first.charAt(0).toUpperCase() + first.slice(1), ...rest].join(' · ')
}

export type FieldCounter = {
  text: string
  exceeded: boolean
}

export function fieldCounter(field: FieldDefinition, value: unknown): FieldCounter | undefined {
  const opts = options(field)

  if (field.type === 'JSON') {
    if (opts.maxBytes == null) return undefined
    const used = new TextEncoder().encode(typeof value === 'string' ? value : '').length
    return { text: `${used} / ${opts.maxBytes} bytes`, exceeded: used > opts.maxBytes }
  }

  if (field.type === 'STRING' || field.type === 'EMAIL' || field.type === 'URL') {
    if (opts.maxLength == null) return undefined
    const used = typeof value === 'string' ? value.length : 0
    return { text: `${used} / ${opts.maxLength}`, exceeded: used > opts.maxLength }
  }

  if (field.type === 'SELECT' && (opts.maxSelect || 1) > 1) {
    const used = Array.isArray(value) ? value.length : 0
    const maxSelect = opts.maxSelect || 1
    return { text: `${used} / ${maxSelect} selected`, exceeded: used > maxSelect }
  }

  return undefined
}
