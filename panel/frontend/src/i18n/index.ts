import {currentCatalog, currentLocale, installCatalog, loadCatalog} from './catalog'
import {translate} from './translate'
import type {Catalog, Message, TranslateParams} from './translate'

/**
 * `@/i18n` - every user-visible sentence in the browser comes through here.
 *
 * <p>Import `t` and call it. There is no provider to wrap a page in and no hook to
 * remember, because the catalogue is fixed for the life of the page: the language is a
 * property of the account, and changing it is a form post and a fresh render from the
 * server.
 *
 * <pre>
 *   import {t} from '@/i18n'
 *
 *   t('project.list.title')
 *   t('project.list.empty', {organization: org.name})
 *   t('service.count', {count: services.length})
 * </pre>
 *
 * <p>Keys are `feature.screen.thing`, matching the folder the string is used in, so a
 * sentence on screen can be found without grepping for its English text - which is
 * exactly what you cannot do once the screen is in Vietnamese.
 */
export function t(key: string, params?: TranslateParams): string {
  return translate(currentCatalog(), currentLocale(), key, params)
}

export function useI18n(): {t: typeof t} {
  return {t}
}

export {installCatalog, loadCatalog, currentLocale}
export type {Catalog, Message, TranslateParams}
