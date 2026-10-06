import { useToast } from '@nuxt/ui/composables/useToast'

const DEFAULT_DURATION = 4000

type ToastInput = {
  title: string
  description?: string
  icon?: string
  color?: string
  duration?: number
  progress?: boolean
}

export function useAppToast() {
  const toast = useToast()

  function add(options: ToastInput) {
    // An error that disappears on its own is easily missed, so it stays until it is closed.
    // A duration of 0 starts no timer.
    const persistent = options.color === 'error'
    const duration = persistent ? 0 : (options.duration ?? DEFAULT_DURATION)
    const entry = toast.add({
      progress: false,
      ...options,
      duration
    })

    if (!persistent) {
      window.setTimeout(() => toast.remove(entry.id), duration + 250)
    }
    return entry
  }

  return {
    toasts: toast.toasts,
    add,
    update: toast.update,
    remove: toast.remove,
    clear: toast.clear
  }
}
