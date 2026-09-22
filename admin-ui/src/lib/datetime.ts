/**
 * Conversion between the value of an `<input type="datetime-local">` and the string a DATETIME
 * field stores. The picker never shows or accepts a zone offset, while DateTimeFieldValidator
 * parses with OffsetDateTime and rejects everything without one - so the offset has to be added
 * here, and it has to be the offset of the *picked* moment: deriving it from "now" would give a
 * January appointment the summer-time offset of the day it was entered.
 *
 * Both functions are pure so the arithmetic stays testable and does not vanish into a component.
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

/**
 * The current local date as the value of an `<input type="date">` (`yyyy-MM-dd`). Used to
 * pre-fill DATE fields of a new record; the editor keeps a way to clear it again.
 */
export function currentDateInputValue(now: Date = new Date()): string {
  return formatDatePart(now)
}

/** The current local time as the value of an `<input type="time">` (`HH:mm:ss`). */
export function currentTimeInputValue(now: Date = new Date()): string {
  return formatTimePart(now)
}

/** The current local moment as the value of an `<input type="datetime-local">`. */
export function currentDateTimeInputValue(now: Date = new Date()): string {
  return `${formatDatePart(now)}T${formatTimePart(now)}`
}

/**
 * Stored value -> value for the picker (`yyyy-MM-ddTHH:mm:ss`, local time of this browser).
 * A stored value with a foreign offset is converted into the local zone; null, undefined and
 * anything unparsable yield an empty string, which leaves the picker blank instead of showing a
 * value nobody can edit.
 */
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
 * Picker value -> value to store, always with an offset. An empty input yields `undefined` (never
 * `''`, which would run into the validator and be rejected). An input the browser never produces
 * and that cannot be parsed is passed through unchanged, so validation reports it instead of the
 * conversion silently inventing a timestamp.
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
