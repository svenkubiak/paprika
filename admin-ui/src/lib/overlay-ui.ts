export const slideoverUi = {
  overlay: 'z-50',
  content: 'z-[51] w-full max-w-xl shadow-xl'
}

export const modalUi = {
  overlay: 'z-50',
  content: 'z-[51] shadow-xl'
}

export const selectMenuUi = {
  content: 'z-[52]'
}

export const selectContentProps = {
  bodyLock: false
}

type OverlayPointerEvent = CustomEvent<{ originalEvent: PointerEvent }>

/** Keep slideovers open when interacting with portaled select menus. */
export function allowPortaledMenuPointerDown(event: OverlayPointerEvent) {
  const target = event.detail?.originalEvent?.target
  if (!(target instanceof Element)) return

  if (
    target.closest('[role="listbox"]') ||
    target.closest('[data-reka-popper-content-wrapper]') ||
    target.closest('[data-reka-select-viewport]')
  ) {
    event.preventDefault()
  }
}

export const slideoverContentProps = {
  disableOutsidePointerEvents: false,
  onPointerDownOutside: allowPortaledMenuPointerDown
}
