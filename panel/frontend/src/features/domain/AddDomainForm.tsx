import {useState} from 'react'

import {useI18n} from '@/i18n'
import {Button, Card, Checkbox, Input, Select, useFormFields} from '@/shell'

import type {QuotaAllowance} from '@/features/org/orgTypes'

/**
 * `POST /services/{serviceId}/domains`.
 *
 * Inline on the page rather than in a dialog, and that is a correctness decision rather
 * than a stylistic one: a rejected write answers with a redirect carrying `errors` as a
 * shared prop, and a dialog is closed by the time that page renders. The customer would
 * see nothing at all - the hostname they typed gone, and no message. On the page, the
 * message lands under the box that produced it.
 *
 * One field is visible. A hostname is all most people need to give, and the three that
 * follow - the port, the redirect target, and turning TLS off while DNS still points
 * somewhere else - are behind a disclosure so a 375px screen shows a text box and a
 * button.
 */
export function AddDomainForm({
  serviceId,
  allowance,
  writable,
}: {
  serviceId: string
  /** `DOMAIN`. The button says why it is off rather than accepting the tap and refusing. */
  allowance: QuotaAllowance
  writable: boolean
}) {
  const {t} = useI18n()
  const form = useFormFields({
    hostname: '',
    tlsMode: 'ON_DEMAND',
    targetPort: '',
    forceHttps: true,
    redirectToHostname: '',
  })
  const [advanced, setAdvanced] = useState(false)

  const spent = allowance.limit > 0 && allowance.used >= allowance.limit
  const disabled = !writable || spent

  return (
    <Card
      title={t('domain.form.title')}
      description={t('domain.form.description')}
    >
      <form
        onSubmit={(event) => {
          event.preventDefault()
          form.submit(`/services/${serviceId}/domains`)
        }}
        className="flex flex-col gap-3"
      >
        <Input
          {...form.bind('hostname')}
          label={t('domain.form.hostname')}
          placeholder={t('domain.form.hostnamePlaceholder')}
          type="text"
          inputMode="url"
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
          enterKeyHint="done"
          required
          disabled={disabled}
          hint={t('domain.form.hostnameHint')}
        />

        <button
          type="button"
          onClick={() => setAdvanced((current) => !current)}
          aria-expanded={advanced}
          className="self-start text-sm font-medium text-accent-600 dark:text-accent-400"
        >
          {advanced ? t('domain.form.toggleAdvanced.hide') : t('domain.form.toggleAdvanced.show')}
        </button>

        {advanced ? (
          <div className="flex flex-col gap-3 rounded-lg border border-ink-200 p-3 dark:border-ink-800">
            <Select
              {...form.bind('tlsMode')}
              label={t('domain.form.tls')}
              disabled={disabled}
              options={[
                {value: 'ON_DEMAND', label: t('domain.form.tls.onDemand')},
                {value: 'OFF', label: t('domain.form.tls.off')},
              ]}
              hint={t('domain.form.tlsHint')}
            />

            <Input
              {...form.bind('targetPort')}
              label={t('domain.form.targetPort')}
              placeholder={t('domain.form.targetPortPlaceholder')}
              type="text"
              inputMode="numeric"
              disabled={disabled}
              hint={t('domain.form.targetPortHint')}
            />

            <Input
              {...form.bind('redirectToHostname')}
              label={t('domain.form.redirectTo')}
              placeholder={t('domain.form.redirectToPlaceholder')}
              type="text"
              inputMode="url"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              disabled={disabled}
              hint={t('domain.form.redirectToHint')}
            />

            <Checkbox
              {...form.check('forceHttps')}
              label={t('domain.form.forceHttps')}
              disabled={disabled}
              hint={t('domain.form.forceHttpsHint')}
            />
          </div>
        ) : null}

        <Button type="submit" block loading={form.processing} disabled={disabled}>
          {t('domain.form.submit')}
        </Button>

        {!writable ? (
          <p className="text-sm text-ink-500 dark:text-ink-400">
            {t('domain.form.readOnly')}
          </p>
        ) : spent ? (
          <p className="text-sm text-ink-500 dark:text-ink-400">
            {t('domain.form.limitReached', {limit: allowance.limit})}
          </p>
        ) : null}
      </form>
    </Card>
  )
}
