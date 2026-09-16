/**
 * Recovery for the one failure the admin UI cannot code around: the server gets updated while a
 * tab is still open, so the content-hashed chunk a lazily imported page lives in is gone. Every
 * navigation into a page that has not been imported yet then fails with a 404, and because the
 * router resolves the page component before the app is mounted, the first such navigation leaves
 * an empty <div id="app"> behind - a white page with nothing on it and no hint that a reload is
 * all it takes.
 *
 * `/login` is where this shows up most, because it is the one page an authenticated tab has
 * usually never loaded: signing out pushes straight into it, and so does a session that ran out
 * after a restart.
 */

const RELOAD_KEY = 'paprika:chunk-reload'

/**
 * Browsers all word this differently, so the message is matched instead of the error type.
 */
const STALE_BUILD_MARKERS = [
  'Failed to fetch dynamically imported module',
  'Importing a module script failed',
  'error loading dynamically imported module',
  'Unable to preload CSS'
]

let reloading = false

export function isStaleBuildError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error)

  return STALE_BUILD_MARKERS.some((marker) => message.includes(marker))
}

/**
 * Reloads the given path so the browser picks up the current index.html and with it the chunk
 * names of the deployed build. Returns false when this tab has already tried that, which is what
 * keeps a chunk that stays unreachable from turning into a reload loop.
 *
 * The flag has to survive the reload, hence sessionStorage - it is cleared again by
 * {@link clearStaleBuildReload} as soon as the app mounts, so a second update in the same tab is
 * recovered just as well.
 */
export function reloadForStaleBuild(path: string): boolean {
  if (reloading) {
    return true
  }

  if (sessionStorage.getItem(RELOAD_KEY)) {
    return false
  }

  reloading = true
  sessionStorage.setItem(RELOAD_KEY, '1')
  window.location.assign(path)

  return true
}

export function clearStaleBuildReload(): void {
  reloading = false
  sessionStorage.removeItem(RELOAD_KEY)
}

/**
 * Last resort once the reload did not help. Built from plain DOM on purpose: at this point a page
 * chunk is known to be unreachable, so the only code that can still be trusted to run is what the
 * entry chunk already brought along.
 */
export function renderStaleBuildNotice(error: unknown): void {
  if (reloading) {
    return
  }

  const root = document.getElementById('app')
  if (!root) {
    return
  }

  const stale = isStaleBuildError(error)

  const frame = document.createElement('div')
  frame.setAttribute(
    'style',
    'color-scheme: light dark; background: Canvas; color: CanvasText; position: fixed; inset: 0;' +
      ' display: flex; align-items: center; justify-content: center; padding: 1.5rem;' +
      ' font-family: system-ui, -apple-system, sans-serif; text-align: center;'
  )

  const box = document.createElement('div')
  box.setAttribute('style', 'max-width: 28rem; display: grid; gap: 0.75rem;')

  const title = document.createElement('h1')
  title.setAttribute('style', 'margin: 0; font-size: 1.125rem; font-weight: 600;')
  title.textContent = 'The admin UI could not be loaded'

  const text = document.createElement('p')
  text.setAttribute('style', 'margin: 0; font-size: 0.875rem; opacity: 0.75; line-height: 1.5;')
  text.textContent = stale
    ? 'This tab is running an older version of the admin UI than the server. Reloading picks up the current one.'
    : 'Something went wrong while starting the admin UI. Reloading usually resolves it.'

  const button = document.createElement('button')
  button.type = 'button'
  button.textContent = 'Reload'
  button.setAttribute(
    'style',
    'justify-self: center; margin-top: 0.25rem; padding: 0.5rem 1rem; border: 0; border-radius: 0.375rem;' +
      ' background: #2563eb; color: #fff; font: inherit; font-size: 0.875rem; cursor: pointer;'
  )
  // No inline handler - the CSP the server sends is script-src 'self'.
  button.addEventListener('click', () => {
    clearStaleBuildReload()
    window.location.reload()
  })

  box.append(title, text, button)
  frame.append(box)
  root.replaceChildren(frame)
}
