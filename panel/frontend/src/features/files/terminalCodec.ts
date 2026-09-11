/**
 * Bytes in both directions, because a PTY carries bytes and SSE carries text.
 *
 * The predecessor's terminal was broken for exactly this reason: bytes were treated as a
 * string somewhere in the middle, so an escape sequence split across a read boundary came
 * out mangled and a paste of anything that was not valid UTF-8 corrupted the stream
 * (design §11.5). `TerminalFrameCodec` on the panel is the other half of this file, and
 * the rule is the same on both sides - convert once, at the edge, and never touch a
 * fragment.
 *
 * Output goes straight into xterm.js as a `Uint8Array`, which decodes it with a decoder
 * that remembers a partial character between writes. Decoding to a string here first
 * would reintroduce the bug this file exists to prevent.
 */

/** `btoa` takes a string of code points 0-255, and a spread of a megabyte overflows. */
const CHUNK = 0x8000

const ENCODER = new TextEncoder()

/** What the customer typed, as the UTF-8 bytes the PTY expects. */
export function textToBytes(text: string): Uint8Array {
  return ENCODER.encode(text)
}

/**
 * xterm's `onBinary` payload, as bytes.
 *
 * That event delivers a string in which every code unit is already one byte - it is how
 * xterm reports input that is not text, such as a bracketed paste of binary content - so
 * it is emphatically not UTF-8 encoded first.
 */
export function binaryToBytes(latin1: string): Uint8Array {
  const bytes = new Uint8Array(latin1.length)
  for (let index = 0; index < latin1.length; index += 1) {
    bytes[index] = latin1.charCodeAt(index) & 0xff
  }
  return bytes
}

/** One `out` frame from the panel, ready to hand to `Terminal.write`. */
export function decodeBase64ToBytes(encoded: string): Uint8Array {
  const binary = atob(encoded)
  const bytes = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index)
  }
  return bytes
}

/** Concatenates keystroke frames that were buffered while a request was in flight. */
export function concatBytes(parts: Uint8Array[]): Uint8Array {
  let total = 0
  for (const part of parts) {
    total += part.length
  }
  const out = new Uint8Array(total)
  let at = 0
  for (const part of parts) {
    out.set(part, at)
    at += part.length
  }
  return out
}

/** What goes in the `data` field of an input frame. */
export function bytesToBase64(bytes: Uint8Array): string {
  let binary = ''
  for (let start = 0; start < bytes.length; start += CHUNK) {
    binary += String.fromCharCode(...bytes.subarray(start, start + CHUNK))
  }
  return btoa(binary)
}
