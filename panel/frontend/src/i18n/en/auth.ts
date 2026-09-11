import type {Catalog} from '../translate'

/**
 * Signing in, the second factor, the profile and API tokens.
 *
 * <p>Keys are `auth.<screen>.<thing>`, matching `frontend/src/features/auth`.
 */
export const messages: Catalog = {
  'auth.profile.language.title': 'Language',
  'auth.profile.language.description':
    'Applies to everything the panel writes, including the messages it generates while you '
    + 'are not looking at it.',
}
