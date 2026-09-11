import {Link} from '@inertiajs/react'
import type {ButtonHTMLAttributes, ReactNode} from 'react'

import {cx} from './cx'
import {Spinner} from './Spinner'

/**
 * Every button and every button-shaped link in the panel.
 *
 * The default height is 2.75rem because that is the smallest target a thumb hits
 * reliably, and most of the people using this are holding a phone. `sm` exists for rows
 * that only ever render on a desktop table; using it on a phone surface is a bug the
 * design review catches, not something the type system can.
 *
 * Passing `href` renders an Inertia `Link` instead of a `button`, so navigation stays a
 * client visit and the styling does not have to be copied onto an anchor.
 */
export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger'
export type ButtonSize = 'md' | 'sm' | 'icon'

const VARIANTS: Record<ButtonVariant, string> = {
  primary: 'bg-accent-600 text-white hover:bg-accent-500 active:bg-accent-600',
  secondary:
    'border border-ink-300 bg-white text-ink-800 hover:bg-ink-100 ' +
    'dark:border-ink-700 dark:bg-ink-900 dark:text-ink-100 dark:hover:bg-ink-800',
  ghost: 'text-ink-700 hover:bg-ink-200/60 dark:text-ink-300 dark:hover:bg-ink-800',
  danger: 'bg-failed text-white hover:opacity-90',
}

const SIZES: Record<ButtonSize, string> = {
  md: 'min-h-11 px-4 text-sm',
  sm: 'min-h-9 px-3 text-sm',
  icon: 'size-11 p-0 text-sm',
}

interface Shared {
  variant?: ButtonVariant
  size?: ButtonSize
  /** Full width. The default on a phone for anything that submits a form. */
  block?: boolean
  /** Swaps the leading icon for a spinner and blocks the click. */
  loading?: boolean
  icon?: ReactNode
  children?: ReactNode
  className?: string
}

export type ButtonProps = Shared &
  Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'children' | 'className'>

export type ButtonLinkProps = Shared & {
  href: string
  /** Inertia visits are GET; a link that writes is a `Button` with an `onClick`. */
  prefetch?: boolean
  target?: string
  rel?: string
  'aria-label'?: string
}

function classes(
  {variant = 'primary', size = 'md', block, className}: Shared,
  disabled: boolean,
): string {
  return cx(
    'inline-flex touch-target items-center justify-center gap-2 rounded-lg font-medium',
    'transition-colors select-none',
    'disabled:cursor-not-allowed disabled:opacity-60',
    VARIANTS[variant],
    SIZES[size],
    block ? 'w-full' : '',
    // A `Link` has no `disabled` attribute, so the anchor variant is told outright.
    disabled ? 'pointer-events-none opacity-60' : '',
    className,
  )
}

export function Button({
  variant,
  size,
  block,
  loading,
  icon,
  children,
  className,
  disabled,
  type = 'button',
  ...rest
}: ButtonProps) {
  const blocked = Boolean(disabled) || Boolean(loading)
  return (
    <button
      {...rest}
      type={type}
      disabled={blocked}
      aria-busy={loading || undefined}
      className={classes({variant, size, block, className}, false)}
    >
      {loading ? <Spinner /> : icon}
      {children}
    </button>
  )
}

/** The same shape, as a client-side navigation. */
export function ButtonLink({
  variant,
  size,
  block,
  loading,
  icon,
  children,
  className,
  href,
  prefetch,
  target,
  rel,
  'aria-label': ariaLabel,
}: ButtonLinkProps) {
  return (
    <Link
      href={href}
      prefetch={prefetch}
      target={target}
      rel={rel}
      aria-label={ariaLabel}
      className={classes({variant, size, block, className}, Boolean(loading))}
    >
      {loading ? <Spinner /> : icon}
      {children}
    </Link>
  )
}
