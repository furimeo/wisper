import {cx} from './cx'

/**
 * The panel's icon set.
 *
 * One file of path data rather than an icon package: the shell needs about twenty
 * glyphs, a dependency would ship several thousand, and tree-shaking an icon library
 * across dynamically imported page chunks is the kind of thing that quietly stops working
 * after a bundler upgrade. Everything here is a 24x24 stroke path on `currentColor`, so
 * an icon inherits the colour and the dark-mode behaviour of whatever it sits in.
 */
const PATHS = {
  close: 'M6 6l12 12M18 6 6 18',
  menu: 'M4 7h16M4 12h16M4 17h16',
  more: 'M12 5.5h.01M12 12h.01M12 18.5h.01',
  chevronRight: 'm9.5 5.5 6.5 6.5-6.5 6.5',
  chevronLeft: 'm14.5 5.5-6.5 6.5 6.5 6.5',
  chevronDown: 'm5.5 9.5 6.5 6.5 6.5-6.5',
  chevronUpDown: 'm8 10 4-4 4 4M8 14l4 4 4-4',
  check: 'm5 12.5 4.5 4.5L19 7.5',
  copy:
    'M9 9V6.5A1.5 1.5 0 0 1 10.5 5h7A1.5 1.5 0 0 1 19 6.5v7a1.5 1.5 0 0 1-1.5 1.5H15' +
    'M6.5 9h7A1.5 1.5 0 0 1 15 10.5v7A1.5 1.5 0 0 1 13.5 19h-7A1.5 1.5 0 0 1 5 17.5v-7A1.5 1.5 0 0 1 6.5 9Z',
  projects:
    'M4 7.5A1.5 1.5 0 0 1 5.5 6h3.2l1.8 2h8A1.5 1.5 0 0 1 20 9.5v7A1.5 1.5 0 0 1 18.5 18h-13A1.5 1.5 0 0 1 4 16.5v-9Z',
  database:
    'M4.5 6.5c0-1.4 3.4-2.5 7.5-2.5s7.5 1.1 7.5 2.5-3.4 2.5-7.5 2.5S4.5 7.9 4.5 6.5Z' +
    'M4.5 6.5v11c0 1.4 3.4 2.5 7.5 2.5s7.5-1.1 7.5-2.5v-11M4.5 12c0 1.4 3.4 2.5 7.5 2.5s7.5-1.1 7.5-2.5',
  backup:
    'M4.5 8.5h15M6 8.5V19a1.5 1.5 0 0 0 1.5 1.5h9A1.5 1.5 0 0 0 18 19V8.5' +
    'M5.5 4.5h13a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1h-13a1 1 0 0 1-1-1v-2a1 1 0 0 1 1-1ZM10 12.5h4',
  organization:
    'M9 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6ZM3.5 19.5c0-2.7 2.5-4.5 5.5-4.5s5.5 1.8 5.5 4.5' +
    'M16 5.5a3 3 0 0 1 0 6M17.5 15.2c1.9.6 3 2.1 3 4.3',
  settings:
    'M12 15.2a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4Z' +
    'M19.4 14.2a1.4 1.4 0 0 0 .3 1.6l.1.1a1.7 1.7 0 1 1-2.4 2.4l-.1-.1a1.4 1.4 0 0 0-2.4 1v.3a1.7 1.7 0 1 1-3.4 0v-.2a1.4 1.4 0 0 0-2.4-1l-.1.1a1.7 1.7 0 1 1-2.4-2.4l.1-.1a1.4 1.4 0 0 0-1-2.4h-.3a1.7 1.7 0 1 1 0-3.4h.2a1.4 1.4 0 0 0 1-2.4l-.1-.1a1.7 1.7 0 1 1 2.4-2.4l.1.1a1.4 1.4 0 0 0 2.4-1v-.3a1.7 1.7 0 1 1 3.4 0v.2a1.4 1.4 0 0 0 2.4 1l.1-.1a1.7 1.7 0 1 1 2.4 2.4l-.1.1a1.4 1.4 0 0 0 1 2.4h.3a1.7 1.7 0 1 1 0 3.4h-.2a1.4 1.4 0 0 0-1.3.8Z',
  node:
    'M5 5.5h14a.5.5 0 0 1 .5.5v3.5a.5.5 0 0 1-.5.5H5a.5.5 0 0 1-.5-.5V6a.5.5 0 0 1 .5-.5Z' +
    'M5 14h14a.5.5 0 0 1 .5.5V18a.5.5 0 0 1-.5.5H5a.5.5 0 0 1-.5-.5v-3.5a.5.5 0 0 1 .5-.5ZM7.5 7.8h.01M7.5 16.3h.01',
  audit:
    'M7 4.5h7.5L19 9v10.5a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1v-14a1 1 0 0 1 1-1ZM14 4.5V9h4.5M9 13h6M9 16.5h4',
  jobs: 'M12 20.5a8.5 8.5 0 1 0 0-17 8.5 8.5 0 0 0 0 17ZM12 7.5V12l3 2',
  plan:
    'M4.5 10.8V6a1.5 1.5 0 0 1 1.5-1.5h4.8a1.5 1.5 0 0 1 1.1.4l7 7a1.5 1.5 0 0 1 0 2.2l-4.8 4.8a1.5 1.5 0 0 1-2.2 0l-7-7a1.5 1.5 0 0 1-.4-1.1ZM8.5 9h.01',
  account: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM4.5 20.5c0-3.6 3.4-6 7.5-6s7.5 2.4 7.5 6',
  shield: 'M12 3.5 5 6v5.5c0 4.2 2.9 7.6 7 9 4.1-1.4 7-4.8 7-9V6l-7-2.5Z',
  // The bow is two half-arcs rather than one. A single arc whose chord is within a
  // rounding error of the diameter is very nearly degenerate: the renderer closes it as
  // best it can and leaves a visible notch where the ends almost meet, which reads as a
  // broken glyph at 20px. Two halves always close.
  key:
    'M8 12a4 4 0 1 0 0 8 4 4 0 1 0 0-8M10.8 13.2 20.5 3.5' +
    'M18 6l2 2M15.5 8.5l2 2',
  signOut: 'M15 8.5V6a1.5 1.5 0 0 0-1.5-1.5h-7A1.5 1.5 0 0 0 5 6v12a1.5 1.5 0 0 0 1.5 1.5h7A1.5 1.5 0 0 0 15 18v-2.5M10.5 12h9M16.5 8.5 20 12l-3.5 3.5',
  sun:
    'M12 16.5a4.5 4.5 0 1 0 0-9 4.5 4.5 0 0 0 0 9ZM12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2' +
    'M5.3 5.3 6.7 6.7M17.3 17.3l1.4 1.4M18.7 5.3l-1.4 1.4M6.7 17.3l-1.4 1.4',
  moon: 'M20 14.2A8.3 8.3 0 0 1 9.8 4 8.5 8.5 0 1 0 20 14.2Z',
  monitor: 'M4.5 5.5h15a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1ZM8.5 19.5h7M12 15.5v4',
  inbox:
    'M4.5 13.5h4l1 2.5h5l1-2.5h4M4.5 13.5 6.8 6a1.5 1.5 0 0 1 1.4-1h7.6a1.5 1.5 0 0 1 1.4 1l2.3 7.5v4.5a1.5 1.5 0 0 1-1.5 1.5h-12a1.5 1.5 0 0 1-1.5-1.5v-4.5Z',
  external: 'M13.5 4.5H19.5V10.5M19.5 4.5 11 13M17 14v4.5a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 5 18.5v-9A1.5 1.5 0 0 1 6.5 8H11',
  refresh:
    'M4 4v5h5M20 20v-5h-5M4 9a9 9 0 0 1 15.36-3.36L20 9M20 15a9 9 0 0 1-15.36 3.36L4 15',
} as const

export type IconName = keyof typeof PATHS

export interface IconProps {
  name: IconName
  /** Announced by a screen reader. Leave unset when adjacent text already says it. */
  label?: string
  className?: string
}

export function Icon({name, label, className}: IconProps) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      className={cx('size-5 shrink-0', className)}
      role={label ? 'img' : 'presentation'}
      aria-label={label}
      aria-hidden={label ? undefined : true}
    >
      <path
        d={PATHS[name]}
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}
