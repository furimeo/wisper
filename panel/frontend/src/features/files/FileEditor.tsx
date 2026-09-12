import {router} from '@inertiajs/react'
import {openSearchPanel} from '@codemirror/search'
import CodeMirror, {EditorView} from '@uiw/react-codemirror'
import type {ReactCodeMirrorRef} from '@uiw/react-codemirror'
import {useCallback, useEffect, useMemo, useRef, useState} from 'react'

import {t} from '@/i18n'
import {Badge, Button, ErrorState, Icon, Spinner, askConfirmation, cx, useDialog, useTheme} from '@/shell'

import {MobileKeyBar} from './MobileKeyBar'
import type {KeyBarKey} from './MobileKeyBar'
import {languageFor} from './editorLanguage'
import {FileRequestFailed, downloadHref, filesBase, readFileText} from './fileRequests'
import type {FileEntryView} from './fileTypes'

/**
 * Editing a file in place, without SSH and without downloading it.
 *
 * This is the screen that has to be good enough to replace `vi` over SSH, because there is
 * no SSH here at all (design §8.2) and the customers who chose this platform still edit
 * their configuration on the server. It fills the window rather than sitting in a card:
 * an editor with twelve visible lines is a text box, and the file somebody came to change
 * is an nginx block or a compose file that does not fit in twelve lines.
 *
 * Four things make it safe rather than merely present.
 *
 * **It cannot silently destroy a file.** `FileText.editable` is false when the read hit the
 * server's ceiling, and the editor then opens read-only - saving a truncated buffer would
 * write the first megabyte over the whole file, which is the one way an editor destroys
 * data without anybody typing anything. A file that decodes as binary is read-only for the
 * same reason: it is shown, because seeing it is how somebody works out what it is, but
 * saving the replacement characters back would corrupt every byte the decoder could not
 * represent.
 *
 * **It cannot silently lose an edit.** Closing with unsaved changes asks; so does
 * navigating away, and so does closing the tab. All three are separate mechanisms because
 * they are three different events, and the customer only finds out which one is missing by
 * losing work to it.
 *
 * **It is usable with one thumb.** The key bar underneath carries the characters a software
 * keyboard buries - braces, slash, pipe, colon - plus Tab and the arrows, and tapping it
 * never dismisses the keyboard.
 *
 * **Find is a button, not only Ctrl+F.** `basicSetup` installs the search panel and its
 * keymap, which is everything a desktop needs and nothing a phone can reach.
 *
 * Its own `<dialog>` rather than the shell's `Modal`, which caps at a centred column: this
 * needs the whole viewport, a body that does not scroll independently of the editor inside
 * it, and a key bar pinned under that editor. `useDialog` is the shared part - the top
 * layer, Escape, focus return and the iOS scroll lock - so nothing about modal behaviour is
 * re-implemented here.
 *
 * Over three hundred lines, deliberately (AGENTS.md §3.2). The language table and the key
 * bar are separate files; what remains is the buffer, the four guards around it and the
 * read that fills it, and a guard that lives in a different file from the buffer it
 * protects is a guard somebody removes without noticing.
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
  const [binary, setBinary] = useState(false)
  const [saving, setSaving] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [wrap, setWrap] = useState(true)
  const [caret, setCaret] = useState({line: 1, column: 1})

  const path = entry?.path ?? null
  const dirty = text !== baseline
  const readOnly = !canWrite || truncated || binary
  const language = useMemo(() => languageFor(entry?.name ?? ''), [entry?.name])
  const extensions = useMemo(
    () => (wrap ? [...language.extensions, EditorView.lineWrapping] : language.extensions),
    [language, wrap],
  )

  useEffect(() => {
    if (path === null) {
      setText('')
      setBaseline('')
      setFailure(null)
      setTruncated(false)
      setBinary(false)
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
        setBinary(looksBinary(file.text))
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
            : t('files.editor.default_read_error'),
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
   * has already returned. The third is the close button, below, which can await.
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
      if (!window.confirm(t('files.editor.leave_confirm'))) {
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
        title: t('files.editor.discard_confirm_title'),
        body: t('files.editor.discard_confirm_body'),
        confirmLabel: t('files.editor.discard_confirm_button'),
        cancelLabel: t('files.editor.discard_cancel_button'),
        tone: 'danger',
      })
      if (!confirmed) {
        return
      }
    }
    onClose()
  }, [dirty, readOnly, onClose])

  const dialog = useDialog(entry !== null, () => void requestClose(), !dirty)

  const save = useCallback(() => {
    if (path === null || readOnly || !dirty) {
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
  }, [path, readOnly, dirty, text, serviceId, rootId])

  // Ctrl/Cmd+S, which is the reflex of everybody this screen is for.
  useEffect(() => {
    if (entry === null) {
      return
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
        event.preventDefault()
        save()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [entry, save])

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
    <dialog
      ref={dialog.ref}
      onCancel={dialog.onCancel}
      onClick={dialog.onClick}
      onClose={dialog.onClose}
      aria-label={entry ? t('files.editor.aria_editing', {path: entry.path}) : t('files.editor.aria_default')}
      className={cx(
        // The UA gives a dialog a centred box with its own max sizes; all of that has to go
        // before it can fill the viewport.
        'm-0 max-h-none max-w-none border-0 bg-transparent p-0 h-dvh w-full',
        // `open:` is load-bearing: `dialog:not([open]) {display: none}` is a user-agent
        // rule, and every author declaration outranks that origin however specific it is.
        'open:flex items-stretch justify-center md:items-center md:p-4',
      )}
    >
      <div className="flex h-dvh w-full flex-col overflow-hidden bg-white md:h-full md:max-w-6xl md:rounded-2xl md:shadow-xl dark:bg-ink-900">
        <header className="flex items-start gap-3 border-b border-ink-200 px-4 py-3 [--safe-top-base:0.75rem] pt-safe dark:border-ink-800">
          <div className="min-w-0 flex-1">
            <h2 className="truncate text-base font-semibold text-ink-900 dark:text-ink-100">
              {entry?.name ?? 'File'}
            </h2>
            <p className="truncate font-mono text-xs text-ink-500 dark:text-ink-400">
              {path}
            </p>
          </div>
          <div className="hidden shrink-0 items-center gap-2 sm:flex">
            <Badge tone="neutral">{language.label}</Badge>
            {dirty ? <Badge tone="degraded">{t('files.editor.badge_unsaved')}</Badge> : null}
            {readOnly ? <Badge tone="neutral">{t('files.editor.badge_readonly')}</Badge> : null}
          </div>
          <button
            type="button"
            onClick={() => void requestClose()}
            aria-label={t('files.editor.close_aria')}
            className="-mr-2 -mt-1 flex touch-target items-center justify-center rounded-lg text-ink-500 hover:bg-ink-100 dark:hover:bg-ink-800"
          >
            <Icon name="close" />
          </button>
        </header>

        <div className="flex flex-wrap items-center gap-2 border-b border-ink-200 px-3 py-2 dark:border-ink-800">
          <Button
            variant="secondary"
            size="sm"
            disabled={loading || failure !== null}
            onClick={() => {
              const view = editor.current?.view
              if (view) {
                openSearchPanel(view)
              }
            }}
          >
            {t('files.editor.find_button')}
          </Button>
          <Button variant="ghost" size="sm" onClick={() => setWrap((on) => !on)}>
            {wrap ? t('files.editor.wrap_on') : t('files.editor.wrap_off')}
          </Button>
          <Button
            variant="ghost"
            size="sm"
            disabled={!dirty}
            onClick={() => setText(baseline)}
            title={dirty ? t('files.editor.revert_title_dirty') : t('files.editor.revert_title_clean')}
          >
            {t('files.editor.revert_button')}
          </Button>
          <span className="ml-auto flex items-center gap-3 text-xs tabular-nums text-ink-500 dark:text-ink-400">
            <span className="sm:hidden">
              {dirty ? t('files.editor.badge_unsaved') : readOnly ? t('files.editor.badge_readonly') : language.label}
            </span>
            <span>
              {t('files.editor.caret_pos', {line: caret.line, column: caret.column})}
            </span>
          </span>
          <Button
            size="sm"
            onClick={save}
            loading={saving}
            disabled={readOnly || !dirty}
            title={
              readOnly
                ? t('files.editor.save_title_readonly')
                : dirty
                  ? t('files.editor.save_title_dirty')
                  : t('files.editor.save_title_clean')
            }
          >
            {t('files.editor.save_button')}
          </Button>
        </div>

        {truncated || binary || (!canWrite && !loading) ? (
          <p
            className={cx(
              'px-4 py-2 text-sm',
              truncated || binary
                ? 'bg-degraded/15 text-ink-800 dark:text-ink-100'
                : 'bg-ink-100 text-ink-700 dark:bg-ink-800 dark:text-ink-200',
            )}
          >
            {truncated
              ? t('files.editor.truncated_banner')
              : binary
                ? t('files.editor.binary_banner')
                : t('files.editor.readonly_banner')}
          </p>
        ) : null}

        <div className="flex min-h-0 flex-1 flex-col">
          {failure ? (
            <ErrorState
              title={t('files.editor.error_title')}
              description={failure}
              onRetry={() => setAttempt((count) => count + 1)}
              action={
                entry ? (
                  <Button
                    variant="secondary"
                    onClick={() => {
                      window.location.href = downloadHref(serviceId, rootId, entry.path)
                    }}
                  >
                    {t('files.editor.download_instead')}
                  </Button>
                ) : null
              }
            />
          ) : loading ? (
            <div className="flex items-center gap-3 px-4 py-10 text-sm text-ink-600 dark:text-ink-400">
              <Spinner />
              {t('files.editor.loading_text')}
            </div>
          ) : (
            <div className="min-h-0 flex-1 overflow-hidden">
              <CodeMirror
                ref={editor}
                value={text}
                height="100%"
                className="h-full text-sm"
                theme={theme.resolved}
                editable={!readOnly}
                readOnly={readOnly}
                extensions={extensions}
                onChange={setText}
                onUpdate={(update) => {
                  if (!update.selectionSet && !update.docChanged) {
                    return
                  }
                  const head = update.state.selection.main.head
                  const line = update.state.doc.lineAt(head)
                  setCaret({line: line.number, column: head - line.from + 1})
                }}
                basicSetup={{
                  lineNumbers: true,
                  highlightActiveLine: true,
                  foldGutter: false,
                  // Autocompletion on a phone fights the software keyboard's own
                  // suggestions, and there is nothing here worth completing against.
                  autocompletion: false,
                }}
              />
            </div>
          )}
        </div>

        <MobileKeyBar
          keys={EDITOR_KEYS}
          onPress={press}
          className={cx('[--safe-bottom-base:0.25rem] pb-safe md:hidden', readOnly ? 'hidden' : '')}
        />
      </div>
    </dialog>
  )
}

/**
 * Whether what came back is text somebody can edit.
 *
 * `ReadFileContent` decodes as UTF-8 with replacement rather than refusing, which is right
 * - opening a `.png` by accident should show something and not a stack trace - but it means
 * the panel has to notice. A NUL byte settles it immediately; otherwise a run of
 * replacement characters well above what a mis-encoded accent or two would produce is the
 * signal. Only the first few kilobytes are examined, because a file that is binary is
 * binary in its first page.
 */
function looksBinary(text: string): boolean {
  const sample = text.slice(0, 4096)
  let replaced = 0
  for (const character of sample) {
    const code = character.codePointAt(0) ?? 0
    if (code === 0) {
      return true
    }
    if (code === 0xfffd) {
      replaced += 1
    }
  }
  return replaced > Math.max(4, sample.length * 0.01)
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
