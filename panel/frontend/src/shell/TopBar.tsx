import {AccountMenu} from './AccountMenu'
import {Breadcrumbs} from './Breadcrumbs'
import {Icon} from './Icon'
import {cx} from './cx'

/**
 * The header: where you are, and who you are.
 *
 * Sticky, because on a phone the trail and the way back up it are the two things a
 * customer looks for after scrolling, and hunting for them by scrolling back is how
 * somebody ends up pressing the browser's back button and losing an unsaved form.
 *
 * The menu button only exists below `md`; above it the sidebar is already on screen and a
 * second way to reach the same links would be one more thing to keep in step.
 */
export function TopBar({onOpenMenu}: {onOpenMenu: () => void}) {
  return (
    <header
      className={cx(
        // py-1.5 and no avatar: the bar is a breadcrumb and a name, and every pixel it
        // takes is one the page below does not get. A 44px circular target in here was
        // taller than the bar's own padding allowed, so it was clipped at the top.
        'sticky top-0 z-30 flex items-center gap-2 border-b border-ink-200 px-3 py-1.5',
        '[--safe-top-base:0.375rem] pt-safe',
        'bg-white/90 backdrop-blur-sm',
        'dark:border-ink-800 dark:bg-ink-950/90',
      )}
    >
      <button
        type="button"
        onClick={onOpenMenu}
        aria-haspopup="dialog"
        aria-label="Open navigation"
        className="flex touch-target shrink-0 items-center justify-center rounded-lg text-ink-700 hover:bg-ink-100 md:hidden dark:text-ink-300 dark:hover:bg-ink-800"
      >
        <Icon name="menu" />
      </button>

      <Breadcrumbs className="min-w-0 flex-1" />

      <AccountMenu />
    </header>
  )
}
