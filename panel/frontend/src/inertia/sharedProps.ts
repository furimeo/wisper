import {usePage} from '@inertiajs/react'

/**
 * Field name to message, as written by `InertiaFlash.errors(...)` on the server. The
 * keys are the `name` attributes of the inputs that produced them, so a form can render
 * each message under the right control without a translation table.
 */
export type FieldErrors = Record<string, string>

/** One-off notices that survive exactly one redirect. */
export type FlashMessages = {
  success?: string
  error?: string
}

/**
 * The props every page receives whatever its controller put in the model.
 *
 * `errors` and `flash` come from `SharedProps.java` and are always objects, never
 * undefined - a page that has to write `errors?.name` in one place and `errors.name` in
 * another eventually gets it wrong where nobody looked. Anything a
 * `SharedPropsContributor` adds on the server belongs in this type too, so a page that
 * reads it gets a compile error when the server stops sending it.
 */
export interface SharedProps {
  errors: FieldErrors
  flash: FlashMessages
  [key: string]: unknown
}

/** The shared props, typed. Page-specific props come from `usePage<YourProps>()`. */
export function useSharedProps(): SharedProps {
  return usePage().props as unknown as SharedProps
}

/** The message for one field, or undefined when that field is fine. */
export function useFieldError(field: string): string | undefined {
  return useSharedProps().errors[field]
}
