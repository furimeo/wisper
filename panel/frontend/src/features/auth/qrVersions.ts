/**
 * The ISO/IEC 18004 tables for versions 1 to 10 at error-correction level M.
 *
 * Ten versions and one level, because there is exactly one thing this encoder ever draws:
 * the `otpauth://` URI from `TotpSecret.provisioningUri`. That is around 130 bytes for a
 * typical address and 213 is what version 10 holds, so the range covers the realistic
 * cases with room to spare while the tables stay short enough to check by eye. A longer
 * payload is refused rather than silently truncated - `TotpEnrolment` falls back to the
 * secret in text, which is a real answer.
 *
 * Level M recovers from about 15% damage. L would fit more in a smaller symbol; M is
 * chosen because the thing being scanned is often a phone screen photographed by another
 * phone at an angle, and the failure mode of "it will not scan" is a customer who cannot
 * turn on their second factor.
 */

export interface QrVersionSpec {
  /** 1 to 10. The symbol is `4 * version + 17` modules on a side. */
  version: number
  /** Data codewords across every block. */
  dataCodewords: number
  /** How many bytes of payload fit once the mode and length header is deducted. */
  byteCapacity: number
  /** Error-correction codewords per block; every block in a version has the same count. */
  eccPerBlock: number
  /** Blocks carrying `shortBlockData` codewords. These come first in the interleave. */
  shortBlocks: number
  shortBlockData: number
  /** Blocks carrying one codeword more. Zero for most versions. */
  longBlocks: number
  /** Centre coordinates of the alignment patterns, empty for version 1. */
  alignment: number[]
}

/**
 * Indexed by version, so index 0 is unused and `VERSIONS[7]` is version 7.
 *
 * Every row satisfies `dataCodewords + eccPerBlock * (shortBlocks + longBlocks)` equals
 * the version's total codeword count, and `shortBlocks * shortBlockData + longBlocks *
 * (shortBlockData + 1)` equals `dataCodewords`. Both are worth re-checking against the
 * standard before touching a number here; a wrong one produces a symbol that renders
 * perfectly and scans as garbage.
 */
const VERSIONS: (QrVersionSpec | null)[] = [
  null,
  {version: 1, dataCodewords: 16, byteCapacity: 14, eccPerBlock: 10, shortBlocks: 1, shortBlockData: 16, longBlocks: 0, alignment: []},
  {version: 2, dataCodewords: 28, byteCapacity: 26, eccPerBlock: 16, shortBlocks: 1, shortBlockData: 28, longBlocks: 0, alignment: [6, 18]},
  {version: 3, dataCodewords: 44, byteCapacity: 42, eccPerBlock: 26, shortBlocks: 1, shortBlockData: 44, longBlocks: 0, alignment: [6, 22]},
  {version: 4, dataCodewords: 64, byteCapacity: 62, eccPerBlock: 18, shortBlocks: 2, shortBlockData: 32, longBlocks: 0, alignment: [6, 26]},
  {version: 5, dataCodewords: 86, byteCapacity: 84, eccPerBlock: 24, shortBlocks: 2, shortBlockData: 43, longBlocks: 0, alignment: [6, 30]},
  {version: 6, dataCodewords: 108, byteCapacity: 106, eccPerBlock: 16, shortBlocks: 4, shortBlockData: 27, longBlocks: 0, alignment: [6, 34]},
  {version: 7, dataCodewords: 124, byteCapacity: 122, eccPerBlock: 18, shortBlocks: 4, shortBlockData: 31, longBlocks: 0, alignment: [6, 22, 38]},
  {version: 8, dataCodewords: 154, byteCapacity: 152, eccPerBlock: 22, shortBlocks: 2, shortBlockData: 38, longBlocks: 2, alignment: [6, 24, 42]},
  {version: 9, dataCodewords: 182, byteCapacity: 180, eccPerBlock: 22, shortBlocks: 3, shortBlockData: 36, longBlocks: 2, alignment: [6, 26, 46]},
  {version: 10, dataCodewords: 216, byteCapacity: 213, eccPerBlock: 26, shortBlocks: 4, shortBlockData: 43, longBlocks: 1, alignment: [6, 28, 50]},
]

/** Thrown when the payload does not fit in version 10. The caller shows text instead. */
export class QrPayloadTooLong extends Error {
  constructor(bytes: number) {
    super(
      `${bytes} bytes will not fit in a version 10 QR code, which holds 213. ` +
        'Show the secret as text instead.',
    )
    this.name = 'QrPayloadTooLong'
  }
}

/** The smallest version that holds `bytes` bytes in byte mode. */
export function smallestVersionFor(bytes: number): QrVersionSpec {
  for (const spec of VERSIONS) {
    if (spec && bytes <= spec.byteCapacity) {
      return spec
    }
  }
  throw new QrPayloadTooLong(bytes)
}

/**
 * Bits in the character-count field, which widens at version 10.
 *
 * Byte mode only. Getting this wrong shifts every bit after the header by eight places,
 * which is the kind of bug that produces a scannable code full of the wrong characters.
 */
export function characterCountBits(version: number): number {
  return version < 10 ? 8 : 16
}
