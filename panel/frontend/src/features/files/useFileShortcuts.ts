import {useEffect, useRef} from 'react'

/**
 * Driving the file manager without the mouse.
 *
 * Somebody who administers a server touches this screen every day, and the difference
 * between a tool they tolerate and one they are fast in is whether their hands have to
 * leave the keyboard to walk a directory tree. The bindings are the ones every file
 * manager since Norton Commander has had, so nothing here has to be learned: arrows move,
 * Enter opens, Backspace goes up, F2 renames, Delete deletes, Ctrl/Cmd+A selects
 * everything, Escape clears, Ctrl/Cmd+L edits the path.
 *
 * Two exclusions matter more than the list. Nothing fires while the focus is in a text
 * control, or the F2 that renames a file would fire while somebody is typing the new name
 * into the dialog it opened. And nothing fires while a `<dialog>` is open, because every
 * overlay in this panel is a real modal dialog and a Delete keypress meant for the
 * confirmation behind it must not delete a second file.
 */
export interface FileShortcuts {
  /** Enter: a folder is walked into, a file is opened in the editor. */
  open: () => void
  /** Backspace: the folder above. */
  up: () => void
  rename: () => void
  remove: () => void
  selectAll: () => void
  clear: () => void
  /** Arrow keys, without Shift. */
  moveCursor: (delta: number) => void
  /** Arrow keys with Shift held: extend the selection to the new row. */
  extendCursor: (delta: number) => void
  /** Space: tick or untick the row the cursor is on. */
  toggleCursor: () => void
  /** Ctrl/Cmd+L: put the caret in the path field. */
  editPath: () => void
}

export function useFileShortcuts(handlers: FileShortcuts, enabled: boolean): void {
  // The listener outlives the render that created it, and every handler closes over state
  // that changes on each one.
  const current = useRef(handlers)
  current.current = handlers

  useEffect(() => {
    if (!enabled) {
      return
    }

    const onKeyDown = (event: KeyboardEvent) => {
      if (isTyping(event.target) || document.querySelector('dialog[open]') !== null) {
        return
      }
      const shortcuts = current.current
      const accel = event.ctrlKey || event.metaKey

      if (accel && event.key.toLowerCase() === 'a') {
        event.preventDefault()
        shortcuts.selectAll()
        return
      }
      if (accel && event.key.toLowerCase() === 'l') {
        event.preventDefault()
        shortcuts.editPath()
        return
      }
      // Cmd+Backspace is how a Mac deletes; plain Backspace is how everything, including a
      // Mac's Finder, goes up a level.
      if (accel && event.key === 'Backspace') {
        event.preventDefault()
        shortcuts.remove()
        return
      }
      if (accel) {
        return
      }

      switch (event.key) {
        case 'ArrowDown':
        case 'ArrowUp': {
          event.preventDefault()
          const delta = event.key === 'ArrowDown' ? 1 : -1
          if (event.shiftKey) {
            shortcuts.extendCursor(delta)
          } else {
            shortcuts.moveCursor(delta)
          }
          return
        }
        case 'Enter':
          event.preventDefault()
          shortcuts.open()
          return
        case 'Backspace':
          event.preventDefault()
          shortcuts.up()
          return
        case 'F2':
          event.preventDefault()
          shortcuts.rename()
          return
        case 'Delete':
          event.preventDefault()
          shortcuts.remove()
          return
        case ' ':
          event.preventDefault()
          shortcuts.toggleCursor()
          return
        case 'Escape':
          shortcuts.clear()
          return
        default:
          return
      }
    }

    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [enabled])
}

/** Whether the keystroke belongs to something the customer is typing into. */
function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false
  }
  if (target.isContentEditable) {
    return true
  }
  const tag = target.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT'
}
