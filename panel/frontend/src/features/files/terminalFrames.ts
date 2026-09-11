/**
 * The terminal socket's wire format, and the other half of `TerminalSocketFrame.java`.
 *
 * Three tags, the same three in both directions - bytes, size, end - which are the three
 * message types in `terminal.proto`. The payload behind a tag differs by direction because
 * the panel knows things this side does not: which container the shell is in, and what it
 * exited with.
 *
 * ```
 * byte 0   tag
 *   0x00   bytes    ->  keystrokes            <-  PTY output
 *   0x01   size     ->  u16 cols, u16 rows    <-  u16 cols, u16 rows, UTF-8 container id
 *   0x02   end      ->  (nothing)             <-  i32 exit code, UTF-8 reason
 * ```
 *
 * Binary frames, so there is no base64 and no encoding step to get wrong. A PTY emits
 * arbitrary bytes - an escape sequence split across a read, a `cat` of something that is
 * not text - and the predecessor's terminal broke precisely because those were treated as
 * a string somewhere in the middle (design §11.5). Output goes into xterm.js as a
 * `Uint8Array`, which decodes it with a decoder that remembers a partial character between
 * writes.
 */

import type {TerminalExit, TerminalReady} from './fileTypes'

const BYTES = 0x00
const SIZE = 0x01
const END = 0x02

/** The panel refuses a longer frame, so a paste of a whole file is split across several. */
export const MAX_FRAME_BYTES = 64 * 1024

const DECODER = new TextDecoder()

/** What the panel just said. */
export type TerminalMessage =
  | {kind: 'output'; data: Uint8Array}
  | {kind: 'ready'; ready: TerminalReady}
  | {kind: 'exit'; exit: TerminalExit}

/** Keystrokes, a paste, or a key from the phone's extra bar. */
export function keystrokeFrame(data: Uint8Array): Uint8Array {
  const frame = new Uint8Array(1 + data.length)
  frame[0] = BYTES
  frame.set(data, 1)
  return frame
}

/**
 * The window changed.
 *
 * Its own frame, never characters written into the shell: a curses application that is
 * never told about a resize draws over itself, and the customer sees a broken screen
 * rather than a wrong one.
 */
export function resizeFrame(columns: number, rows: number): Uint8Array {
  const frame = new Uint8Array(5)
  const view = new DataView(frame.buffer)
  frame[0] = SIZE
  view.setUint16(1, clamp(columns), false)
  view.setUint16(3, clamp(rows), false)
  return frame
}

/** The customer is finished with this shell. Ends the PTY, not just the connection. */
export function exitFrame(): Uint8Array {
  return Uint8Array.of(END)
}

/**
 * One frame from the panel.
 *
 * Returns null for anything this client does not understand rather than throwing. A frame
 * from a newer panel is not a reason to tear down a working shell, and the three tags
 * below are the whole contract - a fourth would be an addition, not a corruption.
 */
export function readFrame(payload: ArrayBuffer): TerminalMessage | null {
  if (payload.byteLength === 0) {
    return null
  }
  const bytes = new Uint8Array(payload)
  const view = new DataView(payload)
  switch (bytes[0]) {
    case BYTES:
      // A view onto the same buffer, not a copy: this is the hot path, and xterm.js
      // consumes the array synchronously.
      return {kind: 'output', data: bytes.subarray(1)}
    case SIZE:
      if (payload.byteLength < 5) {
        return null
      }
      return {
        kind: 'ready',
        ready: {
          columns: view.getUint16(1, false),
          rows: view.getUint16(3, false),
          containerId: DECODER.decode(bytes.subarray(5)),
        },
      }
    case END:
      if (payload.byteLength < 5) {
        return null
      }
      return {
        kind: 'exit',
        exit: {code: view.getInt32(1, false), reason: DECODER.decode(bytes.subarray(5))},
      }
    default:
      return null
  }
}

/** A dimension a `u16` can carry. xterm never reports one outside this; a bug might. */
function clamp(value: number): number {
  return Math.max(0, Math.min(0xffff, Math.trunc(value)))
}
