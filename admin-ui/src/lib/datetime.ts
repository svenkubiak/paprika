/**
 * The picker has no zone offset, but DateTimeFieldValidator requires one. The offset added here
 * must be that of the picked moment, not of now - otherwise DST shifts the stored time.
 */

function pad(value: number, length = 2): string {
  return String(value).padStart(length, '0')
}

/** `Date#getTimezoneOffset` is minutes *behind* UTC, so the sign is inverted for ISO. */
function formatOffset(date: Date): string {
  const minutesBehindUtc = date.getTimezoneOffset()
  const sign = minutesBehindUtc > 0 ? '-' : '+'
  const total = Math.abs(minutesBehindUtc)
  return `${sign}${pad(Math.floor(total / 60))}:${pad(total % 60)}`
}

function formatDatePart(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function formatTimePart(date: Date): string {
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export function currentDateInputValue(now: Date = new Date()): string {
  return formatDatePart(now)
}

export function currentTimeInputValue(now: Date = new Date()): string {
  return formatTimePart(now)
}

export function currentDateTimeInputValue(now: Date = new Date()): string {
  return `${formatDatePart(now)}T${formatTimePart(now)}`
}

/** Converted into the local zone; null and unparsable values yield '' so the picker stays blank. */
export function toDateTimeInputValue(stored: unknown): string {
  if (typeof stored !== 'string') {
    return ''
  }

  const text = stored.trim()
  if (!text) {
    return ''
  }

  const date = new Date(text)
  if (Number.isNaN(date.getTime())) {
    return ''
  }

  return `${formatDatePart(date)}T${formatTimePart(date)}`
}

/**
 * An empty input yields `undefined`, never '' (the validator rejects it). Unparsable input passes
 * through unchanged so validation reports it instead of a timestamp being invented.
 */
export function toDateTimeStoredValue(input: string): string | undefined {
  if (typeof input !== 'string') {
    return undefined
  }

  const text = input.trim()
  if (!text) {
    return undefined
  }

  const date = new Date(text)
  if (Number.isNaN(date.getTime())) {
    return text
  }

  const milliseconds = date.getMilliseconds()
  return (
    `${formatDatePart(date)}T${formatTimePart(date)}` +
    `${milliseconds > 0 ? `.${pad(milliseconds, 3)}` : ''}` +
    formatOffset(date)
  )
}
