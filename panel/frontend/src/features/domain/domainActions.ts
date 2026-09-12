import {router} from '@inertiajs/react'

import {t} from '@/i18n'
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
    title: t('domain.confirm.remove.title', {hostname: domain.hostname}),
    body: t('domain.confirm.remove.body'),
    confirmLabel: t('domain.confirm.remove.confirm'),
    cancelLabel: t('domain.confirm.remove.cancel'),
    tone: 'danger',
    requireText: domain.hostname,
    requireTextLabel: t('domain.confirm.remove.requireTextLabel'),
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
