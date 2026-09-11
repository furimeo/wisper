import {FitAddon} from '@xterm/addon-fit'
import {WebLinksAddon} from '@xterm/addon-web-links'
import {Terminal} from '@xterm/xterm'
import type {ITheme} from '@xterm/xterm'
import {useEffect, useRef} from 'react'

import '@xterm/xterm/css/xterm.css'

/**
 * xterm.js, mounted and kept the right size.
 *
 * Loaded on demand from `TerminalPage`, which is why it is its own file: the renderer and
 * its addons are a couple of hundred kilobytes and nobody looking at a deployment log has
 * asked for them.
 *
 * Sizing is the part that decides whether this is usable on a phone. Three things change
 * the available box and all three have to reach the PTY, or a full-screen program draws
 * over itself: the element being laid out at all, the device rotating, and - the one that
 * only exists on a phone - the software keyboard sliding up and taking half the screen
 * with it. `visualViewport` is the only thing that reports the last one; a plain `resize`
 * listener does not fire for it on iOS.
 *
 * `onResize` is wired from the terminal's own event rather than from the observer, so the
 * panel is told the size the renderer actually settled on. Telling the PTY a size the
 * screen is not is how a customer ends up with lines wrapping in the wrong place.
 */
export interface TerminalHandle {
  /** PTY output, as bytes. xterm decodes them, keeping a partial character between writes. */
  write: (bytes: Uint8Array) => void
  /** Text typed by something other than the keyboard: the paste button, the key bar. */
  paste: (text: string) => void
  focus: () => void
  /** The size the renderer is at right now. */
  size: () => {columns: number; rows: number}
}

const DARK: ITheme = {
  background: '#12161c',
  foreground: '#dfe4ec',
  cursor: '#7fc7ff',
  cursorAccent: '#12161c',
  selectionBackground: '#2c4a63',
  black: '#12161c',
  red: '#f2777a',
  green: '#71c48c',
  yellow: '#e6c07b',
  blue: '#6fb3d2',
  magenta: '#cc99cc',
  cyan: '#66c2c2',
  white: '#dfe4ec',
  brightBlack: '#5a6472',
  brightRed: '#ff9195',
  brightGreen: '#8ee0a6',
  brightYellow: '#f5d38f',
  brightBlue: '#8ecbe6',
  brightMagenta: '#e2b3e2',
  brightCyan: '#84d6d6',
  brightWhite: '#ffffff',
}

const LIGHT: ITheme = {
  background: '#ffffff',
  foreground: '#252b33',
  cursor: '#0f6b9c',
  cursorAccent: '#ffffff',
  selectionBackground: '#cbe3f5',
  black: '#252b33',
  red: '#b03a3d',
  green: '#2f7d4c',
  yellow: '#8a6300',
  blue: '#1c6ea4',
  magenta: '#8b4f9e',
  cyan: '#0f6f70',
  white: '#dfe4ec',
  brightBlack: '#6b7480',
  brightRed: '#c94f52',
  brightGreen: '#3d9660',
  brightYellow: '#a37800',
  brightBlue: '#2a83bd',
  brightMagenta: '#a266b5',
  brightCyan: '#158788',
  brightWhite: '#ffffff',
}

export function TerminalScreen({
  theme,
  onReady,
  onInput,
  onResize,
}: {
  theme: 'light' | 'dark'
  /** Called once, with the handle the page writes output through. */
  onReady: (handle: TerminalHandle) => void
  /** Everything the customer typed, as UTF-8 text or as raw bytes. */
  onInput: (payload: {text: string} | {binary: string}) => void
  onResize: (columns: number, rows: number) => void
}) {
  const host = useRef<HTMLDivElement | null>(null)
  const terminal = useRef<Terminal | null>(null)

  // Read inside the terminal's own listeners, which are registered once and outlive the
  // renders that produced these callbacks.
  const ready = useRef(onReady)
  ready.current = onReady
  const input = useRef(onInput)
  input.current = onInput
  const resized = useRef(onResize)
  resized.current = onResize

  useEffect(() => {
    const container = host.current
    if (!container) {
      return
    }

    const term = new Terminal({
      // Small enough for a phone to get a usable number of columns, large enough to read
      // outdoors. Below 12px a 375px screen gains columns nobody can see.
      fontSize: window.innerWidth < 480 ? 12 : 13,
      fontFamily:
        "ui-monospace, 'JetBrains Mono', 'SF Mono', Menlo, Consolas, 'Liberation Mono', monospace",
      lineHeight: 1.2,
      cursorBlink: true,
      cursorStyle: 'bar',
      // A shell prints a lot and a phone has to hold it. Two thousand lines is a
      // generous screenful of history without making the tab a memory problem.
      scrollback: 2000,
      convertEol: false,
      theme: theme === 'dark' ? DARK : LIGHT,
      // The panel's own key bar sends Escape and Control, so the browser's find should
      // stay the browser's find.
      allowProposedApi: false,
    })
    const fit = new FitAddon()
    term.loadAddon(fit)
    term.loadAddon(
      new WebLinksAddon((event, uri) => {
        event.preventDefault()
        // Opened without a referrer and without a handle back to this window: a link in a
        // customer's own log output is text they do not control the destination of.
        window.open(uri, '_blank', 'noopener,noreferrer')
      }),
    )

    term.open(container)
    terminal.current = term
    fit.fit()

    term.onData((data) => input.current({text: data}))
    term.onBinary((data) => input.current({binary: data}))
    term.onResize(({cols, rows}) => resized.current(cols, rows))

    ready.current({
      write: (bytes) => term.write(bytes),
      paste: (text) => term.paste(text),
      focus: () => term.focus(),
      size: () => ({columns: term.cols, rows: term.rows}),
    })

    let frame = 0
    const refit = () => {
      window.cancelAnimationFrame(frame)
      frame = window.requestAnimationFrame(() => {
        try {
          fit.fit()
        } catch {
          // `fit` throws while the element is detached or has no layout yet, which
          // happens for one frame when a dialog above this closes.
        }
      })
    }

    const observer = new ResizeObserver(refit)
    observer.observe(container)
    window.addEventListener('orientationchange', refit)
    // The software keyboard. It changes the visible box without changing the window, so
    // nothing else reports it.
    window.visualViewport?.addEventListener('resize', refit)

    return () => {
      window.cancelAnimationFrame(frame)
      observer.disconnect()
      window.removeEventListener('orientationchange', refit)
      window.visualViewport?.removeEventListener('resize', refit)
      terminal.current = null
      term.dispose()
    }
  }, [])

  useEffect(() => {
    const term = terminal.current
    if (term) {
      term.options.theme = theme === 'dark' ? DARK : LIGHT
    }
  }, [theme])

  return (
    <div
      ref={host}
      className="h-full w-full overflow-hidden rounded-lg bg-white p-1 dark:bg-[#12161c]"
    />
  )
}
