import {router} from '@inertiajs/react'
import CodeMirror from '@uiw/react-codemirror'
import type {ReactCodeMirrorRef} from '@uiw/react-codemirror'
import {useCallback, useEffect, useMemo, useRef, useState} from 'react'

import {Badge, Button, ErrorState, Modal, Spinner, askConfirmation, useTheme} from '@/shell'

import {MobileKeyBar} from './MobileKeyBar'
import type {KeyBarKey} from './MobileKeyBar'
import {languageFor} from './editorLanguage'
import {FileRequestFailed, filesBase, readFileText} from './fileRequests'
import type {FileEntryView} from './fileTypes'

/**
 * Editing a file in place, without SSH and without downloading it.
 *
 * This is the screen that has to be good enough to replace `vi` over SSH for the ninety
 * per cent of edits that are one line of a config file, because there is no SSH here at
 * all (design §8.2). Three things make that true rather than aspirational.
 *
 * **It cannot silently destroy a file.** `FileText.editable` is false when the read hit
 * the server's ceiling, and the editor then opens read-only - saving a truncated buffer
 * would write the first megabyte over the whole file, which is the one way an editor
 * destroys data without anybody typing anything.
 *
 * **It cannot silently lose an edit.** Closing with unsaved changes asks; so does
 * navigating away, and so does closing the tab. All three are separate mechanisms because
 * they are three different events, and the customer only finds out which one is missing
 * by losing work to it.
 *
 * **It is usable with one thumb.** The key bar underneath carries the characters a
 * software keyboard buries - braces, slash, pipe, colon - plus Tab and the arrows, and
 * tapping it never dismisses the keyboard.
 *
 * Just over three hundred lines, deliberately (AGENTS.md §3.2). The language table and
 * the key bar are separate files; the rest is the buffer, the three guards against losing
 * it and the read that fills it, and a guard that lives in a different file from the
 * buffer it protects is a guard somebody removes without noticing.
 */
export function FileEditor({
  entry,
  onClose,
  serviceId,
  rootId,
  canWrite,
}: {
  /** The file being edited, or null when the editor is shut. */
  entry: FileEntryView | null
  onClose: () => void
  serviceId: string
  rootId: string
  canWrite: boolean
}) {
  const editor = useRef<ReactCodeMirrorRef>(null)
  const theme = useTheme()

  const [text, setText] = useState('')
  const [baseline, setBaseline] = useState('')
  const [loading, setLoading] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const [truncated, setTruncated] = useState(false)
  const [saving, setSaving] = useState(false)
  const [attempt, setAttempt] = useState(0)

  const path = entry?.path ?? null
  const dirty = text !== baseline
  const readOnly = !canWrite || truncated
  const language = useMemo(() => languageFor(entry?.name ?? ''), [entry?.name])

  useEffect(() => {
    if (path === null) {
      setText('')
      setBaseline('')
      setFailure(null)
      setTruncated(false)
      return
    }
    const abort = new AbortController()
    setLoading(true)
    setFailure(null)
    readFileText(serviceId, rootId, path, abort.signal)
      .then((file) => {
        setText(file.text)
        setBaseline(file.text)
        setTruncated(!file.editable)
        setLoading(false)
      })
      .catch((cause: unknown) => {
        if (abort.signal.aborted) {
          return
        }
        setLoading(false)
        setFailure(
          cause instanceof FileRequestFailed
            ? cause.message
            : 'The file could not be read. The node holding it may be unreachable.',
        )
      })
    return () => abort.abort()
  }, [serviceId, rootId, path, attempt])

  /*
   * Leaving with unsaved changes, all three ways it can happen.
   *
   * `beforeunload` covers the tab and the back button out of the application; the Inertia
   * `before` hook covers every in-application visit, including a tap on the breadcrumb
   * behind this dialog. Both have to answer synchronously, which is why they use the
   * browser's own dialog rather than the shell's - a promise cannot cancel an event that
   * has already returned.
   */
  const savingRef = useRef(false)
  savingRef.current = saving

  useEffect(() => {
    if (!dirty) {
      return
    }
    const warnOnUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', warnOnUnload)
    const release = router.on('before', () => {
      if (savingRef.current) {
        return
      }
      if (!window.confirm('This file has changes that have not been saved. Leave anyway?')) {
        return false
      }
      return
    })
    return () => {
      window.removeEventListener('beforeunload', warnOnUnload)
      release()
    }
  }, [dirty])

  const requestClose = useCallback(async () => {
    if (dirty && !readOnly) {
      const confirmed = await askConfirmation({
        title: 'Close without saving?',
        body: 'The changes in this file have not been written to the volume.',
        confirmLabel: 'Discard changes',
        cancelLabel: 'Keep editing',
        tone: 'danger',
      })
      if (!confirmed) {
        return
      }
    }
    onClose()
  }, [dirty, readOnly, onClose])

  const save = () => {
    if (path === null || readOnly) {
      return
    }
    setSaving(true)
    const written = text
    router.post(
      `${filesBase(serviceId)}/content`,
      {rootId, path, text: written},
      {
        // The editor stays open over the refreshed listing. Without this Inertia remounts
        // the page after the redirect and the file somebody is working on disappears.
        preserveState: true,
        preserveScroll: true,
        onSuccess: () => setBaseline(written),
        onFinish: () => setSaving(false),
      },
    )
  }

  const press = (id: string) => {
    const view = editor.current?.view
    if (!view) {
      return
    }
    const range = view.state.selection.main
    if (id === 'left' || id === 'right') {
      const moved = view.moveByChar(range, id === 'right')
      view.dispatch({selection: {anchor: moved.head}, scrollIntoView: true})
    } else if (id === 'up' || id === 'down') {
      const moved = view.moveVertically(range, id === 'down')
      view.dispatch({selection: {anchor: moved.head}, scrollIntoView: true})
    } else if (id === 'home' || id === 'end') {
      const moved = view.moveToLineBoundary(range, id === 'end')
      view.dispatch({selection: {anchor: moved.head}, scrollIntoView: true})
    } else {
      view.dispatch(view.state.replaceSelection(id === 'tab' ? '\t' : id))
    }
    view.focus()
  }

  return (
    <Modal
      open={entry !== null}
      onClose={() => void requestClose()}
      title={entry?.name ?? 'File'}
      description={path ?? undefined}
      size="lg"
      dismissible={!dirty}
      footer={
        <>
          <Button variant="secondary" onClick={() => void requestClose()} block>
            {dirty && !readOnly ? 'Discard' : 'Close'}
          </Button>
          <Button onClick={save} loading={saving} disabled={readOnly || !dirty} block>
            Save
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone="neutral">{language.label}</Badge>
          {dirty ? <Badge tone="degraded">Unsaved</Badge> : null}
          {readOnly ? <Badge tone="neutral">Read-only</Badge> : null}
        </div>

        {truncated ? (
          <p className="rounded-lg bg-degraded/15 px-3 py-2 text-sm text-ink-800 dark:text-ink-100">
            This file is larger than the panel will open, so you are looking at the
            beginning of it. Saving is off: writing this buffer back would replace the whole
            file with the part that was read.
          </p>
        ) : null}
        {!canWrite && !truncated ? (
          <p className="rounded-lg bg-ink-100 px-3 py-2 text-sm text-ink-700 dark:bg-ink-800 dark:text-ink-200">
            This tree is read-only for you, so the file opens for reading. A static site's
            files come from its last build, and an edit here would be replaced by the next
            deployment.
          </p>
        ) : null}

        {failure ? (
          <ErrorState
            title="The file could not be opened"
            description={failure}
            onRetry={() => setAttempt((count) => count + 1)}
          />
        ) : loading ? (
          <div className="flex items-center gap-3 py-10 text-sm text-ink-600 dark:text-ink-400">
            <Spinner />
            Reading the file from the node.
          </div>
        ) : (
          <div className="overflow-hidden rounded-lg border border-ink-200 dark:border-ink-800">
            <CodeMirror
              ref={editor}
              value={text}
              height="55dvh"
              theme={theme.resolved}
              editable={!readOnly}
              readOnly={readOnly}
              extensions={language.extensions}
              onChange={setText}
              basicSetup={{
                lineNumbers: true,
                highlightActiveLine: true,
                foldGutter: false,
                // Autocompletion on a phone fights the software keyboard's own suggestions,
                // and there is nothing here worth completing against.
                autocompletion: false,
              }}
            />
            <MobileKeyBar keys={EDITOR_KEYS} onPress={press} />
          </div>
        )}
      </div>
    </Modal>
  )
}

/**
 * What a software keyboard buries, plus movement.
 *
 * Chosen from what a config file and a shell script are actually made of rather than from
 * an ASCII table: the brackets, the slashes, the pipe, the colon and the quotes, then the
 * arrows for the caret placement a touch screen is bad at.
 */
const EDITOR_KEYS: KeyBarKey[] = [
  {id: 'tab', label: 'Tab', title: 'Tab'},
  {id: '{', label: '{'},
  {id: '}', label: '}'},
  {id: '[', label: '['},
  {id: ']', label: ']'},
  {id: '(', label: '('},
  {id: ')', label: ')'},
  {id: '<', label: '<'},
  {id: '>', label: '>'},
  {id: '/', label: '/'},
  {id: '\\', label: '\\'},
  {id: '|', label: '|'},
  {id: ':', label: ':'},
  {id: ';', label: ';'},
  {id: '"', label: '"'},
  {id: "'", label: "'"},
  {id: '-', label: '-'},
  {id: '_', label: '_'},
  {id: '=', label: '='},
  {id: '$', label: '$'},
  {id: '#', label: '#'},
  {id: '*', label: '*'},
  {id: 'home', label: '⇤', title: 'Start of line'},
  {id: 'left', label: '←', title: 'Left'},
  {id: 'up', label: '↑', title: 'Up'},
  {id: 'down', label: '↓', title: 'Down'},
  {id: 'right', label: '→', title: 'Right'},
  {id: 'end', label: '⇥', title: 'End of line'},
]
