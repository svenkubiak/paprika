// Recovery for a server update under an open tab: the content-hashed chunk of a lazy page is gone,
// so navigating into it fails and the first such navigation leaves a blank page.

/** Shared with public/boot.js - the two recover from the same situation and must not add up. */
const RELOAD_KEY = 'paprika:reload-attempt'
const MAX_ATTEMPTS = 2
const WINDOW_MS = 120_000
const HEALTH_BUDGET_MS = 30_000
const HEALTH_INTERVAL_MS = 1_000

/**
 * Only picks the wording of the notice; recovery does not depend on it, since browsers and
 * proxies word this failure too differently for a complete list.
 */
const STALE_BUILD_MARKERS = [
  'Failed to fetch dynamically imported module',
  'Importing a module script failed',
  'error loading dynamically imported module',
  'Unable to preload CSS',
  'Unable to load module script',
  'Expected a JavaScript',
  'Load failed',
  'NetworkError'
]

let reloading = false

interface ReloadAttempts {
  count: number
  at: number
}

export function isStaleBuildError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error)

  return STALE_BUILD_MARKERS.some((marker) => message.includes(marker))
}

function readAttempts(): number {
  try {
    const raw = sessionStorage.getItem(RELOAD_KEY)
    if (!raw) {
      return 0
    }

    const parsed = JSON.parse(raw) as ReloadAttempts | null
    if (!parsed || typeof parsed.at !== 'number' || Date.now() - parsed.at > WINDOW_MS) {
      return 0
    }

    return typeof parsed.count === 'number' ? parsed.count : 0
  } catch {
    return 0
  }
}

function writeAttempt(count: number): void {
  try {
    sessionStorage.setItem(RELOAD_KEY, JSON.stringify({ count, at: Date.now() } satisfies ReloadAttempts))
  } catch {
    /* Storage can be unavailable; one attempt per load is still better than none. */
  }
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

/** Reloading into a still-restarting server would show an error page and waste an attempt. */
async function waitForServer(): Promise<boolean> {
  const deadline = Date.now() + HEALTH_BUDGET_MS

  for (;;) {
    try {
      const response = await fetch('/health', { cache: 'no-store', credentials: 'same-origin' })
      if (response.ok) {
        return true
      }
    } catch {
      /* Server is not answering yet. */
    }

    if (Date.now() >= deadline) {
      return false
    }

    await sleep(HEALTH_INTERVAL_MS)
  }
}

/**
 * Returns false once the attempts are used up, so an unreachable chunk cannot cause a reload loop.
 * The counter lives in sessionStorage to survive the reload and is time boxed, not once-per-tab.
 */
export function reloadForStaleBuild(path: string): boolean {
  if (reloading) {
    return true
  }

  const attempts = readAttempts()
  if (attempts >= MAX_ATTEMPTS) {
    return false
  }

  reloading = true
  writeAttempt(attempts + 1)

  void waitForServer().then((up) => {
    if (up) {
      window.location.assign(path)
      return
    }

    // The server never came back within the budget; say so instead of leaving the failed page.
    reloading = false
    renderStaleBuildNotice(new Error('The server did not respond.'))
  })

  return true
}

/** True while a recovery reload is on its way, so nothing else paints over the page. */
export function isReloadPending(): boolean {
  return reloading
}

export function clearStaleBuildReload(): void {
  reloading = false
  try {
    sessionStorage.removeItem(RELOAD_KEY)
  } catch {
    /* ignore */
  }
}

/** Plain DOM on purpose: with a page chunk unreachable, only the entry chunk can be trusted. */
export function renderStaleBuildNotice(error: unknown): void {
  const message = error instanceof Error ? error.message : String(error)

  if (isStaleBuildError(error)) {
    renderNotice(
      'The admin UI could not be loaded',
      'This tab is running an older version of the admin UI than the server. Reloading picks up the current one.'
    )
    return
  }

  if (message.includes('did not respond')) {
    renderServerUnreachableNotice()
    return
  }

  renderNotice(
    'The admin UI could not be loaded',
    'Something went wrong while starting the admin UI. Reloading usually resolves it.'
  )
}

/** Deliberately not the login page: a request without an answer says nothing about the session. */
export function renderServerUnreachableNotice(): void {
  renderNotice(
    'The server is not responding',
    'Paprika could not be reached. It may be restarting - reloading in a moment usually resolves it.'
  )
}

let noticeRendered = false

function renderNotice(titleText: string, bodyText: string): void {
  // Several places report a failed navigation (onError, the ready promise, the guard); the first
  // explanation keeps the screen.
  if (noticeRendered) {
    return
  }

  const root = document.getElementById('app')
  if (!root) {
    return
  }

  noticeRendered = true

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
  title.textContent = titleText

  const text = document.createElement('p')
  text.setAttribute('style', 'margin: 0; font-size: 0.875rem; opacity: 0.75; line-height: 1.5;')
  text.textContent = bodyText

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
