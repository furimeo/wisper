/**
 * `@/shell` - the application chrome and the primitives every feature builds on.
 *
 * Feature folders import from here and nowhere else inside this directory, so a
 * primitive can be reshaped without a rename reaching forty files. Nothing in here knows
 * about a specific feature; anything that does belongs in that feature's folder.
 */

// Chrome. `AppLayout` is applied by main.tsx; a page never wraps itself in it.
export {AppLayout} from './AppLayout'
export {PageHeader} from './PageHeader'
export type {PageHeaderProps} from './PageHeader'
export {Breadcrumbs} from './Breadcrumbs'
export {useBreadcrumbs, usePageTitle} from './useBreadcrumbs'
export type {Crumb} from './useBreadcrumbs'
export {
  ACCOUNT_SETTINGS,
  PLATFORM,
  PHONE_TABS,
  WORKSPACE,
  isDestinationActive,
  pathOf,
  sectionFor,
} from './NavigationDestinations'
export type {Destination} from './NavigationDestinations'

// Shared props, typed.
export {
  mayWrite,
  useAccount,
  useCurrentOrganization,
  useOrganizations,
  useShellProps,
} from './shellProps'
export type {
  MemberRole,
  OrganizationStatus,
  OrganizationSummary,
  PlatformRole,
  ShellProps,
  SignedInAccount,
} from './shellProps'
export {useFieldError, useSharedProps} from '@/inertia/sharedProps'
export type {FieldErrors, FlashMessages} from '@/inertia/sharedProps'

// Controls.
export {Button, ButtonLink} from './Button'
export type {ButtonLinkProps, ButtonProps, ButtonSize, ButtonVariant} from './Button'
export {Checkbox} from './Checkbox'
export type {CheckboxProps} from './Checkbox'
export {CopyButton} from './CopyButton'
export type {CopyButtonProps} from './CopyButton'
export {Field, describedBy} from './Field'
export type {FieldProps} from './Field'
export {CONTROL_CLASSES, Input, controlBorder} from './Input'
export type {InputProps} from './Input'
export {Select} from './Select'
export type {SelectOption, SelectProps} from './Select'
export {Textarea} from './Textarea'
export type {TextareaProps} from './Textarea'

// Surfaces.
export {Badge} from './Badge'
export type {BadgeProps, BadgeTone} from './Badge'
export {Card, CardFact, CardFacts} from './Card'
export type {CardProps} from './Card'
export {Drawer} from './Drawer'
export type {DrawerProps} from './Drawer'
export {Modal} from './Modal'
export type {ModalProps, ModalSize} from './Modal'
export {Tabs} from './Tabs'
export type {TabItem, TabsProps} from './Tabs'
export {Pagination} from './Pagination'
export type {PaginationProps} from './Pagination'

// Collections.
export {DataList} from './DataList'
export type {DataListColumn, DataListProps} from './DataList'
export {SwipeRow} from './SwipeRow'
export type {SwipeAction, SwipeRowProps} from './SwipeRow'

// States.
export {EmptyState} from './EmptyState'
export type {EmptyStateProps} from './EmptyState'
export {ErrorState} from './ErrorState'
export type {ErrorStateProps} from './ErrorState'
export {Skeleton, SkeletonList, SkeletonText} from './Skeleton'
export {Spinner} from './Spinner'

// Values.
export {ByteQuota, ByteSize, formatBytes} from './ByteSize'
export type {ByteSizeProps} from './ByteSize'
export {RelativeTime, absolute, relative} from './RelativeTime'
export type {RelativeTimeProps} from './RelativeTime'
export {Icon} from './Icon'
export type {IconName, IconProps} from './Icon'

// Feedback.
export {Toaster} from './Toaster'
export {dismissToast, showToast, toast} from './toastStore'
export type {Toast, ToastRequest, ToastTone} from './toastStore'
export {ConfirmHost} from './ConfirmHost'
export {askConfirmation} from './confirmStore'
export type {ConfirmRequest, ConfirmTone} from './confirmStore'

// Hooks.
export {useClipboard} from './useClipboard'
export type {Clipboard, ClipboardState} from './useClipboard'
export {useEventSource} from './useEventSource'
export type {
  SseConnection,
  SseDecoders,
  SseListeners,
  SseOptions,
  SseStatus,
} from './useEventSource'
export {useFormFields} from './useFormFields'
export type {
  CheckBinding,
  FieldValue,
  FormFields,
  FormValues,
  SubmitOptions,
  TextBinding,
} from './useFormFields'
export {AT_LEAST_LG, AT_LEAST_MD, PREFERS_REDUCED_MOTION, useIsWide, useMediaQuery} from './useMediaQuery'
export {setThemePreference, useTheme} from './useTheme'
export type {Theme, ThemePreference} from './useTheme'
export {useDialog} from './useDialog'
export type {DialogHandles} from './useDialog'

// Requests a feature makes outside Inertia - a chunked upload, a terminal keystroke.
export {csrfHeaders, csrfToken} from './csrf'
export {cx} from './cx'
