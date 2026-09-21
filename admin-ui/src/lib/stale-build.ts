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

/** Shared with public/boot.js - the two recover from the same situation and must not add up. */
const RELOAD_KEY = 'paprika:reload-attempt'
const MAX_ATTEMPTS = 2
const WINDOW_MS = 120_000
const HEALTH_BUDGET_MS = 30_000
const HEALTH_INTERVAL_MS = 1_000

/**
 * Browsers all word this differently, so the message is matched instead of the error type. The
 * list is only used to pick the wording of the notice - recovery itself no longer depends on it,
 * because the list was never complete: Safari says "Load failed", a proxy that answers a chunk
 * with an HTML error page produces a MIME type complaint, and a dead keep-alive connection
 * produces a plain network error. All of them mean the same thing here.
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

/**
 * Reloading into a server that is still restarting replaces the broken page with the browser's
 * error page and uses up an attempt for nothing, so every reload waits for the server first.
 */
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
 * Reloads the given path so the browser picks up the current index.html and with it the chunk
 * names of the deployed build. Returns false when this tab has used up its attempts, which is
 * what keeps a chunk that stays unreachable from turning into a reload loop.
 *
 * The counter has to survive the reload, hence sessionStorage. It is time boxed rather than
 * once-per-tab: an attempt that was spent on a server which had not come back up yet must not
 * disable the recovery for the rest of the session.
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

    // The server never came back within the budget. Say so instead of leaving the tab on
    // whatever the failed navigation left behind.
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

/**
 * Last resort once the reload did not help. Built from plain DOM on purpose: at this point a page
 * chunk is known to be unreachable, so the only code that can still be trusted to run is what the
 * entry chunk already brought along.
 */
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

/**
 * Shown when the server itself is not answering. Deliberately not the login page: a request that
 * never got an answer says nothing about the session, and bouncing a signed-in admin to /login
 * over a restart is what used to happen here.
 */
export function renderServerUnreachableNotice(): void {
  renderNotice(
    'The server is not responding',
    'Paprika could not be reached. It may be restarting - reloading in a moment usually resolves it.'
  )
}

let noticeRendered = false

function renderNotice(titleText: string, bodyText: string): void {
  // A failed navigation is reported by more than one place (onError, the ready promise, the
  // guard). Whoever explained it first keeps the screen - the later, vaguer message must not
  // replace it.
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
