import type {LogEvent} from './statsTypes'

/**
 * Turning runs of output into lines.
 *
 * The stream carries `LogEvent`s, and each one is a run of text rather than a line - the
 * node forwards whatever has arrived, because a container that writes half a line and
 * then thinks for ten seconds would otherwise look hung. So the boundary has to be found
 * here, and the half-written line has to be shown while it is still half-written; a
 * viewer that waits for a newline is the exact behaviour the server refused to implement.
 *
 * Everything is a pure function over a value, so the reducer can be run over a batch of
 * events at once. A chatty container produces hundreds of events a second and a React
 * state update per event would spend a phone's battery on re-rendering text nobody has
 * read yet.
 */
export interface LogLine {
  id: number
  text: string
  stderr: boolean
  at: string
  /**
   * A line the panel wrote, not the container: dropped output, or the end of the stream.
   * Rendered differently, because a customer must be able to tell them apart.
   */
  notice: boolean
}

export interface LogTail {
  lines: LogLine[]
  /** Output with no newline yet. Shown, but not yet a line. */
  pending: LogLine | null
  nextId: number
  /** How much output the node discarded rather than block the process writing it. */
  droppedBytes: number
  /** The container exited, or the cron run returned. */
  ended: boolean
}

export function emptyTail(): LogTail {
  return {lines: [], pending: null, nextId: 1, droppedBytes: 0, ended: false}
}

/**
 * Folds one event in.
 *
 * `limit` is the number of lines kept. A build that prints a hundred thousand lines would
 * otherwise be a hundred thousand DOM nodes on a phone; the newest are the ones somebody
 * came to read.
 */
export function acceptEvent(tail: LogTail, event: LogEvent, limit: number): LogTail {
  let lines = tail.lines
  let pending = tail.pending
  let id = tail.nextId
  let dropped = tail.droppedBytes

  if (event.droppedBytes > 0) {
    dropped += event.droppedBytes
    lines = [
      ...lines,
      {
        id: id++,
        text: `--- ${event.droppedBytes.toLocaleString()} bytes of output were dropped: the container wrote faster than this could be read ---`,
        stderr: false,
        at: event.at,
        notice: true,
      },
    ]
  }

  if (event.text !== '') {
    const carried = pending?.text ?? ''
    const carriedStderr = pending?.stderr ?? event.stderr
    const carriedAt = pending?.at ?? event.at
    const segments = (carried + event.text).split('\n')
    const last = segments.pop() ?? ''

    const completed: LogLine[] = segments.map((segment, index) => ({
      id: id++,
      // A container that writes CRLF should not leave a carriage return at the end of
      // every line, which renders as a stray glyph in a monospace font.
      text: segment.endsWith('\r') ? segment.slice(0, -1) : segment,
      stderr: index === 0 ? carriedStderr : event.stderr,
      at: index === 0 ? carriedAt : event.at,
      notice: false,
    }))
    lines = completed.length > 0 ? [...lines, ...completed] : lines
    pending =
      last === ''
        ? null
        : {
            id: id++,
            text: last,
            stderr: segments.length === 0 ? carriedStderr : event.stderr,
            at: segments.length === 0 ? carriedAt : event.at,
            notice: false,
          }
  }

  if (event.end) {
    if (pending) {
      lines = [...lines, pending]
      pending = null
    }
    lines = [
      ...lines,
      {
        id: id++,
        text: '--- the source ended: the container exited, or the run finished ---',
        stderr: false,
        at: event.at,
        notice: true,
      },
    ]
  }

  if (lines.length > limit) {
    lines = lines.slice(lines.length - limit)
  }

  return {lines, pending, nextId: id, droppedBytes: dropped, ended: tail.ended || event.end}
}

/** Everything to draw, including the line that is still being written. */
export function visibleLines(tail: LogTail): LogLine[] {
  return tail.pending ? [...tail.lines, tail.pending] : tail.lines
}

/** The whole tail as text, for the copy button. */
export function tailText(tail: LogTail): string {
  return visibleLines(tail)
    .map((line) => line.text)
    .join('\n')
}
