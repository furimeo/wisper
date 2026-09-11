import {errorCorrectionCodewords} from './qrGaloisField'
import {characterCountBits, smallestVersionFor, type QrVersionSpec} from './qrVersions'

/**
 * Turns a string into the interleaved codeword sequence a QR symbol carries.
 *
 * Byte mode throughout. An `otpauth://` URI is mixed case with `:`, `/`, `?`, `&` and `%`
 * in it, none of which alphanumeric mode can express, so the mode choice is forced rather
 * than chosen.
 */

/** Mode indicator for byte mode, four bits. */
const BYTE_MODE = 0b0100

/** The two values the standard alternates as padding, after the terminator. */
const PAD_BYTES = [0xec, 0x11]

export interface QrCodewords {
  spec: QrVersionSpec
  /** Data and error correction, interleaved, ready to place on the grid. */
  codewords: Uint8Array
}

/** @throws QrPayloadTooLong when the text does not fit in version 10 at level M */
export function encodeToCodewords(text: string): QrCodewords {
  const payload = new TextEncoder().encode(text)
  const spec = smallestVersionFor(payload.length)
  const data = buildDataCodewords(payload, spec)
  return {spec, codewords: interleave(data, spec)}
}

/**
 * Header, payload, terminator and padding, packed into `spec.dataCodewords` bytes.
 *
 * The padding is not optional: a data region that stops early leaves the rest of the
 * symbol holding whatever the placement loop last wrote, and a decoder reads it.
 */
function buildDataCodewords(payload: Uint8Array, spec: QrVersionSpec): Uint8Array {
  const bits: number[] = []
  const push = (value: number, width: number) => {
    for (let i = width - 1; i >= 0; i--) {
      bits.push((value >>> i) & 1)
    }
  }

  push(BYTE_MODE, 4)
  push(payload.length, characterCountBits(spec.version))
  for (const byte of payload) {
    push(byte, 8)
  }

  const capacityBits = spec.dataCodewords * 8
  // Terminator: up to four zeroes, fewer when the payload almost fills the version.
  push(0, Math.min(4, capacityBits - bits.length))
  // Then to a byte boundary, because codewords are bytes.
  push(0, (8 - (bits.length % 8)) % 8)

  const codewords = new Uint8Array(spec.dataCodewords)
  for (let i = 0; i < bits.length; i++) {
    codewords[i >>> 3] = codewords[i >>> 3]! | (bits[i]! << (7 - (i & 7)))
  }
  for (let i = bits.length / 8, pad = 0; i < spec.dataCodewords; i++, pad++) {
    codewords[i] = PAD_BYTES[pad % 2]!
  }
  return codewords
}

/**
 * Splits the data into blocks, computes each block's correction, and interleaves both.
 *
 * The interleave is what makes the correction worth having: a scratch across the symbol
 * damages one or two codewords of every block rather than destroying one block entirely,
 * and a block can only recover from so much.
 */
function interleave(data: Uint8Array, spec: QrVersionSpec): Uint8Array {
  const blocks: Uint8Array[] = []
  const corrections: Uint8Array[] = []

  let offset = 0
  const total = spec.shortBlocks + spec.longBlocks
  for (let i = 0; i < total; i++) {
    // Short blocks first, which is the order the standard interleaves them in: the extra
    // codeword of a long block is then the last thing written in the data section.
    const length = i < spec.shortBlocks ? spec.shortBlockData : spec.shortBlockData + 1
    const block = data.slice(offset, offset + length)
    offset += length
    blocks.push(block)
    corrections.push(errorCorrectionCodewords(block, spec.eccPerBlock))
  }

  const result = new Uint8Array(spec.dataCodewords + spec.eccPerBlock * total)
  let written = 0
  const longest = spec.longBlocks > 0 ? spec.shortBlockData + 1 : spec.shortBlockData
  for (let i = 0; i < longest; i++) {
    for (const block of blocks) {
      if (i < block.length) {
        result[written++] = block[i]!
      }
    }
  }
  for (let i = 0; i < spec.eccPerBlock; i++) {
    for (const correction of corrections) {
      result[written++] = correction[i]!
    }
  }
  return result
}
