import type {KeyBarKey} from './MobileKeyBar'

/**
 * The keys a phone keyboard cannot send, and the bytes they mean.
 *
 * A shell is driven by characters a software keyboard either does not have - Escape, Tab,
 * the arrows, Control - or buries two layers deep, which is where the pipe, the slash and
 * the braces live. Without this bar the web terminal is a viewer: you can read `top` and
 * you cannot quit it.
 *
 * Everything here is a byte sequence and never a synthesised `KeyboardEvent`. xterm.js
 * turns keys into bytes and the panel forwards bytes; inserting a fake event in the
 * middle would mean two places deciding what Escape is, which is the shape of the bug
 * that broke the predecessor's terminal.
 */

/**
 * What each key sends, built from character codes rather than written as escapes.
 *
 * A literal control character inside a string in a source file survives every editor and
 * every diff tool right up until the one that does not, and the failure is invisible in
 * review. `String.fromCharCode(27)` says Escape out loud.
 */
const ESC = String.fromCharCode(27)

const SEQUENCES: Record<string, string> = {
  esc: ESC,
  tab: String.fromCharCode(9),
  // The arrows, in xterm's normal (non-application) cursor mode.
  up: `${ESC}[A`,
  down: `${ESC}[B`,
  right: `${ESC}[C`,
  left: `${ESC}[D`,
  home: `${ESC}[H`,
  end: `${ESC}[F`,
  '^c': String.fromCharCode(3),
  '^d': String.fromCharCode(4),
  '^z': String.fromCharCode(26),
}

/**
 * The strip, with Control shown as held when it is.
 *
 * Control is sticky rather than a chord because a touch screen has no chords: the
 * customer taps Ctrl, the key lights up, and the next character - from this bar or from
 * the software keyboard - is sent as a control byte. A modifier with no visible state is
 * a guess about what the next tap will do.
 */
export function terminalKeys(controlArmed: boolean): KeyBarKey[] {
  return [
    {id: 'esc', label: 'Esc', title: 'Escape'},
    {id: 'tab', label: 'Tab', title: 'Tab'},
    {id: 'ctrl', label: 'Ctrl', title: 'Control', sticky: true, pressed: controlArmed},
    {id: '^c', label: '^C', title: 'Control C, interrupt'},
    {id: '^d', label: '^D', title: 'Control D, end of input'},
    {id: '^z', label: '^Z', title: 'Control Z, suspend'},
    {id: 'up', label: '↑', title: 'Up'},
    {id: 'down', label: '↓', title: 'Down'},
    {id: 'left', label: '←', title: 'Left'},
    {id: 'right', label: '→', title: 'Right'},
    {id: 'home', label: '⇤', title: 'Start of line'},
    {id: 'end', label: '⇥', title: 'End of line'},
    {id: '|', label: '|'},
    {id: '/', label: '/'},
    {id: '\\', label: '\\'},
    {id: '~', label: '~'},
    {id: '-', label: '-'},
    {id: '_', label: '_'},
    {id: ':', label: ':'},
    {id: ';', label: ';'},
    {id: '{', label: '{'},
    {id: '}', label: '}'},
    {id: '[', label: '['},
    {id: ']', label: ']'},
    {id: '(', label: '('},
    {id: ')', label: ')'},
    {id: '$', label: '$'},
    {id: '*', label: '*'},
    {id: '&', label: '&'},
    {id: '>', label: '>'},
    {id: "'", label: "'"},
    {id: '"', label: '"'},
    {id: '`', label: '`'},
  ]
}

/**
 * What one key on the bar sends.
 *
 * Returns null for `ctrl`, which is a modifier and produces nothing on its own.
 */
export function textForKey(id: string): string | null {
  if (id === 'ctrl') {
    return null
  }
  return SEQUENCES[id] ?? id
}

/**
 * A character typed while Control is held, as the byte a terminal expects.
 *
 * `@` through `_` map to 0-31 by clearing the top bits, which is what a keyboard's
 * Control key does in hardware - so Ctrl-C is 3 and Ctrl-[ is Escape. Lower case is
 * folded up first, and `?` is the delete character, which is the one that does not follow
 * the rule.
 */
export function controlByteFor(character: string): number | null {
  if (character.length !== 1) {
    return null
  }
  if (character === '?') {
    return 127
  }
  const code = character.toUpperCase().charCodeAt(0)
  if (code >= 64 && code <= 95) {
    return code - 64
  }
  if (code === 32) {
    // Ctrl-Space is the null byte, which some editors read as "set mark".
    return 0
  }
  return null
}
