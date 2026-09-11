/**
 * The four penalty rules ISO/IEC 18004 uses to choose a mask pattern.
 *
 * All eight masks produce a decodable symbol; the rules exist to pick the one least
 * likely to confuse a scanner. Skipping the scoring and always using mask 0 is a tempting
 * shortcut that works on a good day and fails on a photograph of a screen at an angle,
 * which is precisely the case this QR code has to survive.
 *
 * `modules` is row-major, one byte per module, 1 for dark.
 */

/** Five or more in a row: 3, plus one for each module past the fifth. */
const RUN = 3
/** A 2x2 block of one colour. */
const BLOCK = 3
/** A finder-like 1:1:3:1:1 sequence, the pattern a scanner uses to locate the symbol. */
const FINDER_LIKE = 40
/** Each 5% the dark proportion strays from half. */
const IMBALANCE = 10

export function maskPenalty(modules: Uint8Array, size: number): number {
  const dark = (x: number, y: number) => modules[y * size + x] === 1

  let score = 0
  score += lineRuns(size, dark, false)
  score += lineRuns(size, dark, true)

  for (let y = 0; y < size - 1; y++) {
    for (let x = 0; x < size - 1; x++) {
      const colour = dark(x, y)
      if (colour === dark(x + 1, y) && colour === dark(x, y + 1) && colour === dark(x + 1, y + 1)) {
        score += BLOCK
      }
    }
  }

  let darkCount = 0
  for (const module of modules) {
    darkCount += module
  }
  const total = size * size
  // How many whole 5% steps the dark share is away from 50%, rounded up.
  const steps = Math.ceil(Math.abs(darkCount * 20 - total * 10) / total) - 1
  return score + Math.max(steps, 0) * IMBALANCE
}

/**
 * Rules 1 and 3 along every row (or column), which share one pass.
 *
 * The run history is the last seven alternating run lengths, newest first. That is
 * everything the finder-like test needs, and keeping it as a sliding window means the
 * whole line is scored in one traversal.
 */
function lineRuns(size: number, dark: (x: number, y: number) => boolean, vertical: boolean): number {
  let score = 0
  for (let line = 0; line < size; line++) {
    const history = new Int32Array(7)
    let runColour = false
    let runLength = 0

    for (let along = 0; along < size; along++) {
      const colour = vertical ? dark(line, along) : dark(along, line)
      if (colour === runColour) {
        runLength++
        if (runLength === 5) {
          score += RUN
        } else if (runLength > 5) {
          score++
        }
      } else {
        addRun(history, runLength, size)
        if (!runColour) {
          score += countFinderLike(history) * FINDER_LIKE
        }
        runColour = colour
        runLength = 1
      }
    }
    score += terminateRun(history, runColour, runLength, size) * FINDER_LIKE
  }
  return score
}

/** Pushes one run onto the history, padding the first with the light border outside. */
function addRun(history: Int32Array, runLength: number, size: number): void {
  let length = runLength
  if (history[0] === 0) {
    length += size
  }
  history.copyWithin(1, 0, history.length - 1)
  history[0] = length
}

/** Closes the line: the trailing run gets the same imaginary light border. */
function terminateRun(
  history: Int32Array,
  runColour: boolean,
  runLength: number,
  size: number,
): number {
  let length = runLength
  if (runColour) {
    addRun(history, length, size)
    length = 0
  }
  addRun(history, length + size, size)
  return countFinderLike(history)
}

/**
 * How many 1:1:3:1:1 patterns with a four-wide light margin the history ends in.
 *
 * Zero, one or two: the margin can be on either side, and a run history can satisfy both.
 */
function countFinderLike(history: Int32Array): number {
  const unit = history[1]!
  const core =
    unit > 0 &&
    history[2] === unit &&
    history[3] === unit * 3 &&
    history[4] === unit &&
    history[5] === unit
  if (!core) {
    return 0
  }
  return (
    (history[0]! >= unit * 4 && history[6]! >= unit ? 1 : 0) +
    (history[6]! >= unit * 4 && history[0]! >= unit ? 1 : 0)
  )
}
