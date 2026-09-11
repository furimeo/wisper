import {router} from '@inertiajs/react'
import type {ChangeEvent} from 'react'
import {useCallback, useMemo, useRef, useState} from 'react'

import {useShellProps} from './shellProps'

/**
 * A form, bound to the way this panel actually answers a write.
 *
 * `docs/contracts/panel-http.md` fixes the shape of every write: POST, a multipart body,
 * and a redirect back carrying `errors` and `flash` as shared props. That last part is
 * why this exists rather than a bare `router.post` at each call site - the messages for
 * the form the customer just submitted arrive attached to the *page*, keyed by the `name`
 * attribute of the input that produced them, and every form in the panel would otherwise
 * repeat the same lookup and the same "clear it once they start fixing it" behaviour.
 *
 * The seed comes from the shared prop and every later answer comes from the visit's own
 * `onError`, because `router.post` preserves component state: after a failed write the
 * page does not remount, so a value read once at mount would be the previous attempt's
 * for the rest of the session.
 *
 * It stays thin on purpose. There is no client-side validation here and there will not
 * be: the server validates, and a second copy of the rules in TypeScript is a second
 * place for them to be wrong.
 */
export type FieldValue = string | number | boolean | null | File | File[] | string[]

export type FormValues = Record<string, FieldValue>

/**
 * The body `router.post` accepts.
 *
 * Named off the router rather than imported from `@inertiajs/core`, which is a transitive
 * dependency this package does not declare. It is also what the visit is instantiated
 * with below: leaving the generic to be inferred from `data` would type every callback
 * against this hook's own `T`, and the caller's options - typed against the default -
 * would then be rejected as contravariant. Every `FieldValue` is a legal member of it, so
 * pinning it costs nothing.
 */
type Payload = NonNullable<Parameters<typeof router.post>[1]>

/** The options `router.post` accepts, minus the body, which this hook supplies. */
export type SubmitOptions = NonNullable<Parameters<typeof router.post>[2]>

/** Spreadable onto `Input`, `Select` and `Textarea`. */
export interface TextBinding {
  name: string
  value: string
  onChange: (
    event: ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>,
  ) => void
  error: string | undefined
}

/** Spreadable onto `Checkbox`. */
export interface CheckBinding {
  name: string
  checked: boolean
  onChange: (event: ChangeEvent<HTMLInputElement>) => void
  error: string | undefined
}

export interface FormFields<T extends FormValues> {
  data: T
  set: <K extends keyof T & string>(name: K, value: T[K]) => void
  patch: (values: Partial<T>) => void
  reset: () => void
  /** Text, number, select and textarea inputs. */
  bind: <K extends keyof T & string>(name: K) => TextBinding
  /** Checkboxes and switches. */
  check: <K extends keyof T & string>(name: K) => CheckBinding
  /** The message for one field, whether the server sent it or `setError` recorded it. */
  error: (name: string) => string | undefined
  errors: Record<string, string>
  hasErrors: boolean
  /** Records a message the server has not sent - a confirmation that does not match. */
  setError: (name: string, message: string) => void
  clearErrors: () => void
  processing: boolean
  /** 0-100 while a body carrying files uploads, null otherwise. */
  progress: number | null
  dirty: boolean
  submit: (url: string, options?: SubmitOptions) => void
}

export function useFormFields<T extends FormValues>(initial: T): FormFields<T> {
  const seed = useShellProps().errors
  const initialRef = useRef(initial)

  const [data, setData] = useState<T>(initial)
  const [processing, setProcessing] = useState(false)
  const [progress, setProgress] = useState<number | null>(null)
  const [messages, setMessages] = useState<Record<string, string>>(() => ({...seed}))

  const set = useCallback(<K extends keyof T & string>(name: K, value: T[K]) => {
    setData((current) => ({...current, [name]: value}))
    setMessages((current) => without(current, name))
  }, [])

  const patch = useCallback((values: Partial<T>) => {
    setData((current) => ({...current, ...values}))
    setMessages((current) => {
      let next = current
      for (const name of Object.keys(values)) {
        next = without(next, name)
      }
      return next
    })
  }, [])

  const reset = useCallback(() => {
    setData(initialRef.current)
    setMessages({})
  }, [])

  const bind = useCallback(
    <K extends keyof T & string>(name: K): TextBinding => ({
      name,
      value: data[name] == null ? '' : String(data[name]),
      onChange: (event) => set(name, event.target.value as T[K]),
      error: messages[name],
    }),
    [data, messages, set],
  )

  const check = useCallback(
    <K extends keyof T & string>(name: K): CheckBinding => ({
      name,
      checked: data[name] === true,
      onChange: (event) => set(name, event.target.checked as T[K]),
      error: messages[name],
    }),
    [data, messages, set],
  )

  const submit = useCallback(
    (url: string, options: SubmitOptions = {}) => {
      router.post<Payload>(url, data, {
        // A write answers with a redirect, so without this the customer is thrown to the
        // top of a long settings page to look for a banner they were already next to.
        preserveScroll: true,
        ...options,
        onStart: (visit) => {
          setProcessing(true)
          setProgress(null)
          options.onStart?.(visit)
        },
        onProgress: (event) => {
          setProgress(event?.percentage ?? null)
          options.onProgress?.(event)
        },
        onError: (returned) => {
          setMessages({...returned})
          options.onError?.(returned)
        },
        onSuccess: (page) => {
          setMessages({})
          return options.onSuccess?.(page)
        },
        onFinish: (visit) => {
          setProcessing(false)
          setProgress(null)
          options.onFinish?.(visit)
        },
      })
    },
    [data],
  )

  const dirty = useMemo(() => {
    const start: FormValues = initialRef.current
    const current: FormValues = data
    for (const key of new Set([...Object.keys(start), ...Object.keys(current)])) {
      if (start[key] !== current[key]) {
        return true
      }
    }
    return false
  }, [data])

  return {
    data,
    set,
    patch,
    reset,
    bind,
    check,
    error: (name) => messages[name],
    errors: messages,
    hasErrors: Object.keys(messages).length > 0,
    setError: (name, message) => setMessages((current) => ({...current, [name]: message})),
    clearErrors: () => setMessages({}),
    processing,
    progress,
    dirty,
    submit,
  }
}

function without(messages: Record<string, string>, name: string): Record<string, string> {
  if (!(name in messages)) {
    return messages
  }
  const next = {...messages}
  delete next[name]
  return next
}
