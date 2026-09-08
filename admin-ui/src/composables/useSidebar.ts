import { ref } from 'vue'

const mobileSidebarOpen = ref(false)

export function useSidebar() {
  function openMobileSidebar(event: MouseEvent) {
    event.preventDefault()
    event.stopPropagation()
    mobileSidebarOpen.value = true
  }

  function closeMobileSidebar() {
    mobileSidebarOpen.value = false
  }

  return {
    mobileSidebarOpen,
    openMobileSidebar,
    closeMobileSidebar
  }
}
