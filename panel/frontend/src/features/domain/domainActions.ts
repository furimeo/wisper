import {router} from '@inertiajs/react'

import {askConfirmation} from '@/shell'

import type {DomainView} from './domainTypes'

/**
 * The three writes on a hostname, each with the amount of friction it has earned.
 *
 * Checking is free and reversible, so it is a bare button. Promoting is reversible by
 * promoting something else, so it is a bare button too. Removing is neither: the name goes
 * back into the global pool the moment the row does, and anybody on the platform may then
 * claim it - which is precisely why `domain.hostname` is unique across every tenant. That
 * one asks for the hostname to be typed.
 *
 * All three answer with a redirect and a flash the shell's `Toaster` shows, so nothing
 * here reports anything itself. `preserveScroll` keeps somebody who acted on the fourth
 * hostname down the page next to it.
 */

/** `POST /services/{serviceId}/domains/{domainId}/verify` - the "Check now" button. */
export function checkDomainNow(serviceId: string, domain: DomainView): void {
  router.post(
    `/services/${serviceId}/domains/${domain.id}/verify`,
    {},
    {preserveScroll: true},
  )
}

/** `POST /services/{serviceId}/domains/{domainId}/primary`. */
export function makePrimaryDomain(serviceId: string, domain: DomainView): void {
  router.post(
    `/services/${serviceId}/domains/${domain.id}/primary`,
    {},
    {preserveScroll: true},
  )
}

/** `POST /services/{serviceId}/domains/{domainId}/remove`, behind the hostname typed out. */
export async function removeDomain(serviceId: string, domain: DomainView): Promise<void> {
  const confirmed = await askConfirmation({
    title: `Remove ${domain.hostname}?`,
    body:
      'The node stops answering for it and its certificate is dropped. A hostname is ' +
      'unique across the whole platform, so once this row is gone anybody may add the ' +
      'same name to their own service - remove the DNS record too if you are done with it.',
    confirmLabel: 'Remove this hostname',
    cancelLabel: 'Keep it',
    tone: 'danger',
    requireText: domain.hostname,
    requireTextLabel: 'Type the hostname to confirm',
  })
  if (!confirmed) {
    return
  }
  router.post(
    `/services/${serviceId}/domains/${domain.id}/remove`,
    {},
    {preserveScroll: true},
  )
}
