/*
 * Boot watchdog.
 *
 * Everything the admin UI does to recover from a server update lives inside the bundle, which
 * covers exactly the cases where the bundle still runs. It does not cover the one that produces
 * a truly empty document: the entry chunk or the stylesheet of index.html itself never arrives.
 * That happens around a restart - the shell is answered by the process that is going away while
 * the hashed files it names are already gone, a reverse proxy answers the subresource with a 502,
 * or a keep-alive connection dies between the two requests. Browsers retry the document but not
 * a module subresource, so the tab is left with an empty <div id="app"> until someone reloads.
 *
 * This file is deliberately:
 *   - not part of the bundle, so a broken bundle cannot take it down with it,
 *   - not content-hashed (it lives in public/), so index.html can name it across versions,
 *   - plain ES5-ish script, no imports, so it runs even if module loading is what is broken,
 *   - free of inline code, because the server sends script-src 'self'.
 *
 * The reload budget is shared with the bundle's stale-build recovery (same sessionStorage key),
 * so the two can never add up to a reload loop.
 */
(function () {
  'use strict'

  var RELOAD_KEY = 'paprika:reload-attempt'
  /** Attempts inside the window below. Two, because the first one can hit a server that is still
   *  coming up even though /health answered. */
  var MAX_ATTEMPTS = 2
  var WINDOW_MS = 120000
  /** How long the app gets to put something into #app before this counts as a failed boot. */
  var WATCHDOG_MS = 12000
  /** How long to wait for the server to answer /health before giving up on reloading. */
  var HEALTH_BUDGET_MS = 30000
  var HEALTH_INTERVAL_MS = 1000

  var recovering = false

  var boot = {
    /** Set by main.ts once the app is mounted. */
    mounted: false
  }
  window.__paprikaBoot = boot

  function readAttempts() {
    try {
      var raw = window.sessionStorage.getItem(RELOAD_KEY)
      if (!raw) {
        return 0
      }

      var parsed = JSON.parse(raw)
      if (!parsed || typeof parsed.at !== 'number' || Date.now() - parsed.at > WINDOW_MS) {
        return 0
      }

      return typeof parsed.count === 'number' ? parsed.count : 0
    } catch (error) {
      return 0
    }
  }

  function writeAttempt(count) {
    try {
      window.sessionStorage.setItem(RELOAD_KEY, JSON.stringify({ count: count, at: Date.now() }))
    } catch (error) {
      /* Private mode without storage: one recovery attempt per load is still better than none. */
    }
  }

  function clearAttempts() {
    try {
      window.sessionStorage.removeItem(RELOAD_KEY)
    } catch (error) {
      /* ignore */
    }
  }

  boot.clearReloadBudget = clearAttempts

  function appIsEmpty() {
    var root = document.getElementById('app')
    return !root || root.childElementCount === 0
  }

  /**
   * Reloading while the server is still restarting would just swap the empty page for the
   * browser's "site can't be reached" and burn an attempt, so the reload waits for /health.
   */
  function whenServerIsUp(onUp, onTimeout) {
    var deadline = Date.now() + HEALTH_BUDGET_MS

    function probe() {
      fetch('/health', { cache: 'no-store', credentials: 'same-origin' }).then(
        function (response) {
          if (response.ok) {
            onUp()
          } else {
            retry()
          }
        },
        retry
      )
    }

    function retry() {
      if (Date.now() >= deadline) {
        onTimeout()
        return
      }
      window.setTimeout(probe, HEALTH_INTERVAL_MS)
    }

    probe()
  }

  function notice(message) {
    var root = document.getElementById('app')
    if (!root) {
      return
    }

    var frame = document.createElement('div')
    frame.setAttribute(
      'style',
      'color-scheme: light dark; background: Canvas; color: CanvasText; position: fixed; inset: 0;' +
        ' display: flex; align-items: center; justify-content: center; padding: 1.5rem;' +
        ' font-family: system-ui, -apple-system, sans-serif; text-align: center;'
    )

    var box = document.createElement('div')
    box.setAttribute('style', 'max-width: 28rem; display: grid; gap: 0.75rem;')

    var title = document.createElement('h1')
    title.setAttribute('style', 'margin: 0; font-size: 1.125rem; font-weight: 600;')
    title.textContent = 'The admin UI could not be loaded'

    var text = document.createElement('p')
    text.setAttribute('style', 'margin: 0; font-size: 0.875rem; opacity: 0.75; line-height: 1.5;')
    text.textContent = message

    var button = document.createElement('button')
    button.type = 'button'
    button.textContent = 'Reload'
    button.setAttribute(
      'style',
      'justify-self: center; margin-top: 0.25rem; padding: 0.5rem 1rem; border: 0; border-radius: 0.375rem;' +
        ' background: #2563eb; color: #fff; font: inherit; font-size: 0.875rem; cursor: pointer;'
    )
    button.addEventListener('click', function () {
      clearAttempts()
      window.location.reload()
    })

    box.appendChild(title)
    box.appendChild(text)
    box.appendChild(button)
    frame.appendChild(box)
    root.replaceChildren(frame)
  }

  /**
   * Single entry point for "this tab is showing nothing and only a fresh document can fix it".
   */
  function recover(reason) {
    if (recovering || boot.mounted) {
      return
    }
    recovering = true

    var attempts = readAttempts()
    if (attempts >= MAX_ATTEMPTS) {
      recovering = false
      notice(
        'This tab could not load the current version of the admin UI (' +
          reason +
          '). Reloading may help; if it does not, the server is still updating.'
      )
      return
    }

    writeAttempt(attempts + 1)

    whenServerIsUp(
      function () {
        window.location.reload()
      },
      function () {
        recovering = false
        notice('The server did not respond. It may still be restarting.')
      }
    )
  }

  boot.recover = recover

  /**
   * Resource errors do not bubble, so this has to listen in the capture phase. A failed module
   * script or stylesheet is reported here and nowhere else - there is no JS left to catch it.
   */
  window.addEventListener(
    'error',
    function (event) {
      var target = event.target
      if (!target || target === window) {
        return
      }

      var tag = target.tagName
      if (tag !== 'SCRIPT' && tag !== 'LINK') {
        return
      }

      var src = target.src || target.href || ''
      if (src.indexOf('/assets/') === -1) {
        return
      }

      recover('an asset of this version is no longer available')
    },
    true
  )

  /**
   * Covers everything the error event does not: a script that parsed but threw, a navigation
   * that never resolved, a chunk request that hangs. If nothing rendered by now, nothing will.
   */
  window.setTimeout(function () {
    if (!boot.mounted && appIsEmpty()) {
      recover('it did not start')
    }
  }, WATCHDOG_MS)
})()
