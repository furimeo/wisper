/**
 * Turning what the customer typed into the bytes a PTY expects.
 *
 * The predecessor's terminal was broken for exactly this reason: bytes were treated as a
 * string somewhere in the middle, so an escape sequence split across a read boundary came
 * out mangled and a paste of anything that was not valid UTF-8 corrupted the stream
 * (design §11.5). The rule is convert once, at the edge, and never touch a fragment.
 *
 * Only one direction lives here now. Output arrives on the socket as binary and goes
 * straight into xterm.js as a `Uint8Array`, which decodes it with a decoder that remembers
 * a partial character between writes - so there is nothing to convert on the way in, and
 * no base64 in either direction. `terminalFrames.ts` is the frame format around these
 * bytes; `TerminalSocketFrame.java` is the other half of it.
 */

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
