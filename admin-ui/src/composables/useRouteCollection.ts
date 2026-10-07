import { computed } from 'vue'
import { useRoute } from 'vue-router'

/**
 * The collection of the current route. The <Suspense> in App.vue keeps a page on screen while the
 * next route loads, and that page already sees the new route: without a collection there, it
 * keeps its own instead of turning into "undefined" and loading that.
 */
export function useRouteCollection() {
  const route = useRoute()
  return computed<string>((previous) =>
    route.params.collection ? String(route.params.collection) : (previous ?? '')
  )
}
