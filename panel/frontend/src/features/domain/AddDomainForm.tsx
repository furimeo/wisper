import {useState} from 'react'

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
      title="Add a hostname"
      description="Any name you control can point at this service. wisper checks DNS, then obtains a certificate for it."
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
          label="Hostname"
          placeholder="app.example.com"
          type="text"
          inputMode="url"
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
          enterKeyHint="done"
          required
          disabled={disabled}
          hint="Without a scheme and without a path. Wildcards are not accepted: a certificate for one needs a DNS challenge, and wisper does not manage DNS."
        />

        <button
          type="button"
          onClick={() => setAdvanced((current) => !current)}
          aria-expanded={advanced}
          className="self-start text-sm font-medium text-accent-600 dark:text-accent-400"
        >
          {advanced ? 'Hide the extra options' : 'Port, redirect and TLS options'}
        </button>

        {advanced ? (
          <div className="flex flex-col gap-3 rounded-lg border border-ink-200 p-3 dark:border-ink-800">
            <Select
              {...form.bind('tlsMode')}
              label="TLS"
              disabled={disabled}
              options={[
                {value: 'ON_DEMAND', label: 'Obtain a certificate automatically'},
                {value: 'OFF', label: 'Plain HTTP, no certificate'},
              ]}
              hint="Turn it off while the name still serves a live site elsewhere - the node will stop retrying a certificate it cannot get yet."
            />

            <Input
              {...form.bind('targetPort')}
              label="Port on the container"
              placeholder="Use the service’s own port"
              type="text"
              inputMode="numeric"
              disabled={disabled}
              hint="Only for a service listening on more than one port. Leave it empty otherwise."
            />

            <Input
              {...form.bind('redirectToHostname')}
              label="Redirect to another hostname"
              placeholder="example.com"
              type="text"
              inputMode="url"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              disabled={disabled}
              hint="Serve a permanent redirect instead of the service. This is how www.example.com sends visitors to example.com."
            />

            <Checkbox
              {...form.check('forceHttps')}
              label="Redirect plain HTTP to HTTPS"
              disabled={disabled}
              hint="Only takes effect once the hostname is verified. Redirecting to a port whose handshake cannot succeed turns “not set up yet” into “broken”."
            />
          </div>
        ) : null}

        <Button type="submit" block loading={form.processing} disabled={disabled}>
          Add hostname
        </Button>

        {!writable ? (
          <p className="text-sm text-ink-500 dark:text-ink-400">
            You have read access to this organization, so hostnames cannot be added from
            here.
          </p>
        ) : spent ? (
          <p className="text-sm text-ink-500 dark:text-ink-400">
            This organization is using all {allowance.limit} of the hostnames its plan
            allows. Remove one, or ask an operator to raise the limit.
          </p>
        ) : null}
      </form>
    </Card>
  )
}
