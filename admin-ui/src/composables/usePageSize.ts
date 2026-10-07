import { useLocalStorage } from '@vueuse/core'

/**
 * The rows-per-page choice of a table, kept across visits. Tables that show the same kind of rows
 * share a key, so the choice follows from one to the other. A stored value that is not one of the
 * table's options, an old or hand-edited one, falls back to the default.
 */
export function usePageSize(key: string, fallback: number, options: readonly { value: number }[]) {
  const pageSize = useLocalStorage(`paprika:page-size:${key}`, fallback)
  if (!options.some((option) => option.value === pageSize.value)) {
    pageSize.value = fallback
  }
  return pageSize
}
