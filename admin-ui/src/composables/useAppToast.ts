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
    const duration = options.duration ?? DEFAULT_DURATION
    const entry = toast.add({
      progress: false,
      ...options,
      duration
    })

    window.setTimeout(() => toast.remove(entry.id), duration + 250)
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
