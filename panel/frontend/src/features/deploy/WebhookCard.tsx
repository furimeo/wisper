import {useState} from 'react'

import {Badge, Card, CopyButton, Tabs} from '@/shell'
import type {TabItem} from '@/shell'

import type {DeploymentTarget} from './deployTypes'
import type {WebhookProvider} from './deployVocabulary'
import {webhookUrl} from './deployVocabulary'

/**
 * The URL to paste into GitHub or GitLab so a push deploys.
 *
 * Shown on the deployments page rather than hidden in settings because this is where
 * somebody is standing when they wonder why their push did nothing. The address is built
 * from `location.origin`: the panel sits behind a tunnel and does not reliably know its
 * own public name, and the address the customer reached it on is the only one it can be
 * certain about.
 *
 * The secret is deliberately not here. `Service.webhookSecret` is an encrypted envelope
 * that never leaves the panel - putting it in a page's props would hand it to every
 * browser cache between here and the customer - so the card says where to find it instead
 * of pretending it can show it.
 */
export function WebhookCard({target}: {target: DeploymentTarget}) {
  const [provider, setProvider] = useState<WebhookProvider>('github')
  const url = webhookUrl(target, provider)

  const items: TabItem[] = [
    {value: 'github', label: 'GitHub'},
    {value: 'gitlab', label: 'GitLab'},
  ]

  return (
    <Card
      title="Deploy on push"
      action={
        <Badge tone={target.autoDeploy ? 'running' : 'neutral'} dot>
          {target.autoDeploy ? 'On' : 'Off'}
        </Badge>
      }
      description={
        target.autoDeploy
          ? `Every push to ${target.repositoryBranch ?? 'the configured branch'} starts a deployment.`
          : 'Automatic deployment is off, so a push is recorded and nothing is built.'
      }
    >
      <Tabs
        label="Git provider"
        items={items}
        value={provider}
        onSelect={(value) => setProvider(value === 'gitlab' ? 'gitlab' : 'github')}
        className="mb-4"
      />

      <p className="mb-2 text-sm text-ink-600 dark:text-ink-400">
        {provider === 'github'
          ? 'Add this as a webhook with content type application/json and the push event only.'
          : 'Add this under Settings → Webhooks with the push events trigger.'}
      </p>

      <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
        <code className="min-w-0 flex-1 overflow-x-auto rounded-lg bg-ink-100 px-3 py-2.5 font-mono text-sm whitespace-nowrap text-ink-800 dark:bg-ink-800 dark:text-ink-100">
          {url}
        </code>
        <CopyButton
          value={url}
          label="Copy"
          describedAs={`Copy the ${provider} webhook URL`}
          className="sm:shrink-0"
        />
      </div>

      <p className="mt-3 text-sm text-ink-500 dark:text-ink-400">
        {provider === 'github'
          ? 'GitHub signs each delivery with this service’s webhook secret and wisper checks it before reading a byte of the body. '
          : 'GitLab sends this service’s webhook secret as a token header and wisper checks it before reading a byte of the body. '}
        The secret was generated when the service was created and the panel does not
        display it again. A delivery signed with anything else is answered 401, and the
        reason appears in the provider’s own delivery log.
      </p>
    </Card>
  )
}
