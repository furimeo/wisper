import {encodeToCodewords} from './qrEncode'
import {maskPenalty} from './qrPenalty'

/**
 * Lays the codewords out as a QR symbol: function patterns, data, mask, format bits.
 *
 * The order matters and is the order of the standard. Function patterns go down first and
 * mark their cells reserved, so the zigzag that places data can simply skip anything
 * already claimed; the format area is reserved by drawing it with a placeholder mask,
 * because which mask wins is not known until all eight have been scored.
 */

export interface QrSymbol {
  /** Modules on a side, `4 * version + 17`. The quiet zone is not included. */
  size: number
  /** Row-major, one byte per module, 1 for dark. */
  modules: Uint8Array
}

interface Grid {
  size: number
  modules: Uint8Array
  /** 1 where a function pattern or the format area sits: off limits to data and masking. */
  reserved: Uint8Array
}

/** @throws QrPayloadTooLong when the text needs more than a version 10 symbol */
export function buildQrSymbol(text: string): QrSymbol {
  const {spec, codewords} = encodeToCodewords(text)
  const size = spec.version * 4 + 17
  const grid: Grid = {
    size,
    modules: new Uint8Array(size * size),
    reserved: new Uint8Array(size * size),
  }

  /*
   * Timing first, finders second, and the order is not a preference.
   *
   * Row 6 and column 6 run the whole width of the symbol, straight through where the
   * finder patterns sit. Inside a finder those modules are not alternating: they are the
   * solid inner edge of its outer ring, seven dark modules with one light separator past
   * them. Drawing the timing lines afterwards would stripe that edge, and a 7x7 eye whose
   * bottom row reads 1010101 fails the 1:1:3:1:1 ratio a scanner locates the symbol by -
   * a code that renders perfectly and never scans.
   */
  for (let i = 0; i < size; i++) {
    setFunction(grid, 6, i, i % 2 === 0)
    setFunction(grid, i, 6, i % 2 === 0)
  }
  drawFinder(grid, 3, 3)
  drawFinder(grid, size - 4, 3)
  drawFinder(grid, 3, size - 4)
  drawAlignmentPatterns(grid, spec.alignment)
  // Reserves the format area. The real bits are written once the mask is chosen.
  drawFormatBits(grid, 0)
  drawVersionBits(grid, spec.version)
  drawCodewords(grid, codewords)

  let chosen = 0
  let best = Number.POSITIVE_INFINITY
  for (let mask = 0; mask < 8; mask++) {
    applyMask(grid, mask)
    drawFormatBits(grid, mask)
    const penalty = maskPenalty(grid.modules, size)
    if (penalty < best) {
      best = penalty
      chosen = mask
    }
    // XOR is its own inverse, so the same call undoes it.
    applyMask(grid, mask)
  }
  applyMask(grid, chosen)
  drawFormatBits(grid, chosen)

  return {size, modules: grid.modules}
}

/** Writes a module and marks it as belonging to a function pattern. Ignores overflow. */
function setFunction(grid: Grid, x: number, y: number, dark: boolean): void {
  if (x < 0 || y < 0 || x >= grid.size || y >= grid.size) {
    return
  }
  const index = y * grid.size + x
  grid.modules[index] = dark ? 1 : 0
  grid.reserved[index] = 1
}

/** The 7x7 eye plus its light separator, clipped at the edges of the symbol. */
function drawFinder(grid: Grid, centreX: number, centreY: number): void {
  for (let dy = -4; dy <= 4; dy++) {
    for (let dx = -4; dx <= 4; dx++) {
      const ring = Math.max(Math.abs(dx), Math.abs(dy))
      setFunction(grid, centreX + dx, centreY + dy, ring !== 2 && ring !== 4)
    }
  }
}

/**
 * A 5x5 alignment pattern at every crossing of the version's coordinates, except the
 * three corners already occupied by finder patterns.
 */
function drawAlignmentPatterns(grid: Grid, coordinates: number[]): void {
  const last = coordinates.length - 1
  for (let i = 0; i <= last; i++) {
    for (let j = 0; j <= last; j++) {
      if ((i === 0 && j === 0) || (i === 0 && j === last) || (i === last && j === 0)) {
        continue
      }
      const centreX = coordinates[i]!
      const centreY = coordinates[j]!
      for (let dy = -2; dy <= 2; dy++) {
        for (let dx = -2; dx <= 2; dx++) {
          setFunction(grid, centreX + dx, centreY + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1)
        }
      }
    }
  }
}

/**
 * Fifteen bits, twice: level M and the mask, protected by a BCH(15,5) code and XORed with
 * 0x5412 so an all-light symbol is not a valid format string.
 */
function drawFormatBits(grid: Grid, mask: number): void {
  // Level M's format indicator is 0b00, hence just the mask in the low three bits.
  const data = mask
  let remainder = data
  for (let i = 0; i < 10; i++) {
    remainder = (remainder << 1) ^ ((remainder >>> 9) * 0x537)
  }
  const bits = ((data << 10) | remainder) ^ 0x5412
  const bit = (i: number) => ((bits >>> i) & 1) === 1
  const size = grid.size

  for (let i = 0; i <= 5; i++) {
    setFunction(grid, 8, i, bit(i))
  }
  setFunction(grid, 8, 7, bit(6))
  setFunction(grid, 8, 8, bit(7))
  setFunction(grid, 7, 8, bit(8))
  for (let i = 9; i < 15; i++) {
    setFunction(grid, 14 - i, 8, bit(i))
  }

  for (let i = 0; i < 8; i++) {
    setFunction(grid, size - 1 - i, 8, bit(i))
  }
  for (let i = 8; i < 15; i++) {
    setFunction(grid, 8, size - 15 + i, bit(i))
  }
  // The module that is dark in every symbol ever printed.
  setFunction(grid, 8, size - 8, true)
}

/** Eighteen bits in two blocks near the far corners. Version 7 and up only. */
function drawVersionBits(grid: Grid, version: number): void {
  if (version < 7) {
    return
  }
  let remainder = version
  for (let i = 0; i < 12; i++) {
    remainder = (remainder << 1) ^ ((remainder >>> 11) * 0x1f25)
  }
  const bits = (version << 12) | remainder
  for (let i = 0; i < 18; i++) {
    const dark = ((bits >>> i) & 1) === 1
    const far = grid.size - 11 + (i % 3)
    const near = Math.floor(i / 3)
    setFunction(grid, far, near, dark)
    setFunction(grid, near, far, dark)
  }
}

/**
 * The zigzag: two-module columns from the right edge leftwards, alternating up and down,
 * skipping the vertical timing pattern in column 6 and every reserved module.
 */
function drawCodewords(grid: Grid, data: Uint8Array): void {
  const size = grid.size
  const available = data.length * 8
  let placed = 0

  for (let right = size - 1; right >= 1; right -= 2) {
    // Column 6 is the timing pattern; the pair to its left is 5 and 4.
    if (right === 6) {
      right = 5
    }
    for (let step = 0; step < size; step++) {
      for (let column = 0; column < 2; column++) {
        const x = right - column
        const upward = ((right + 1) & 2) === 0
        const y = upward ? size - 1 - step : step
        const index = y * size + x
        if (grid.reserved[index] === 0 && placed < available) {
          grid.modules[index] = (data[placed >>> 3]! >>> (7 - (placed & 7))) & 1
          placed++
        }
      }
    }
  }
}

/** Inverts every data module the pattern selects. Reserved modules are never touched. */
function applyMask(grid: Grid, mask: number): void {
  const size = grid.size
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const index = y * size + x
      if (grid.reserved[index] === 0 && inverts(mask, x, y)) {
        grid.modules[index] = grid.modules[index]! ^ 1
      }
    }
  }
}

/** The eight mask conditions, verbatim from the standard's table 10. */
function inverts(mask: number, x: number, y: number): boolean {
  switch (mask) {
    case 0:
      return (x + y) % 2 === 0
    case 1:
      return y % 2 === 0
    case 2:
      return x % 3 === 0
    case 3:
      return (x + y) % 3 === 0
    case 4:
      return (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0
    case 5:
      return ((x * y) % 2) + ((x * y) % 3) === 0
    case 6:
      return (((x * y) % 2) + ((x * y) % 3)) % 2 === 0
    default:
      return (((x + y) % 2) + ((x * y) % 3)) % 2 === 0
  }
}
