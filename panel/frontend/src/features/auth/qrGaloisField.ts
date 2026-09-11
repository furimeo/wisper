/**
 * Reed-Solomon error correction over GF(2^8), the arithmetic a QR code is built on.
 *
 * This exists because the TOTP secret must not leave the browser. The obvious way to put
 * a QR code on the enrolment screen is an image URL from somebody else's server, which
 * hands the shared secret of a second factor to a third party in a query string that ends
 * up in their access log. Rendering it here costs three small files and leaks nothing.
 *
 * The field is the one ISO/IEC 18004 specifies: GF(2^8) modulo
 * x^8 + x^4 + x^3 + x^2 + 1, which is 0x11D. Multiplication is done by shift-and-add
 * rather than by logarithm tables - eight iterations is cheap at this size, and it removes
 * the two module-level lookup tables that would otherwise have to be initialised before
 * anything else in the file could run.
 */

/** The field's reducing polynomial. */
const MODULUS = 0x11d

/** One product in GF(2^8). Both operands and the result are single bytes. */
function multiply(x: number, y: number): number {
  let product = 0
  for (let bit = 7; bit >= 0; bit--) {
    product = (product << 1) ^ ((product >>> 7) * MODULUS)
    product ^= ((y >>> bit) & 1) * x
  }
  return product & 0xff
}

/**
 * The divisor polynomial for `degree` error-correction codewords.
 *
 * Returned without its leading coefficient, which is always 1 - the remainder loop below
 * relies on that, and carrying it would mean an extra element that is never read.
 */
function divisorPolynomial(degree: number): Uint8Array {
  const divisor = new Uint8Array(degree)
  divisor[degree - 1] = 1
  let root = 1
  for (let i = 0; i < degree; i++) {
    for (let j = 0; j < degree; j++) {
      divisor[j] = multiply(divisor[j]!, root)
      if (j + 1 < degree) {
        divisor[j] = divisor[j]! ^ divisor[j + 1]!
      }
    }
    root = multiply(root, 0x02)
  }
  return divisor
}

/**
 * The `count` error-correction codewords that follow one block of data codewords.
 *
 * Polynomial long division, one data byte at a time: the remainder register shifts left,
 * and the divisor scaled by the byte that fell off the top is added back in.
 */
export function errorCorrectionCodewords(data: Uint8Array, count: number): Uint8Array {
  const divisor = divisorPolynomial(count)
  const remainder = new Uint8Array(count)
  for (const byte of data) {
    const factor = byte ^ remainder[0]!
    remainder.copyWithin(0, 1)
    remainder[count - 1] = 0
    for (let i = 0; i < count; i++) {
      remainder[i] = remainder[i]! ^ multiply(divisor[i]!, factor)
    }
  }
  return remainder
}
