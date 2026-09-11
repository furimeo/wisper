import {Badge, ByteSize, Card, CardFact, CardFacts, CopyButton, RelativeTime} from '@/shell'

import {formatCores} from '@/features/org/quotaVocabulary'

import type {ServiceView} from './serviceTypes'
import {isolationLabel, kindLabel, presetLabel, restartPolicyLabel} from './serviceVocabulary'

/**
 * What the service is made of, read-only.
 *
 * Two cards in one file because they answer one question - "what did I configure here?" -
 * and a customer opening the overview reads them as one block. Splitting them would
 * produce two files that are always imported together and always changed together.
 *
 * The branch on kind is not cosmetic. `ServiceShape` refuses an image, a port, a command
 * and a health check on a static site, and refuses nothing about a build on an app, so
 * the two kinds genuinely have different facts. Printing "Image: none" against a site
 * would be a row that answers a question the site cannot be asked.
 *
 * Everything here is editable on Settings. Nothing here is editable here: the overview is
 * the screen people open forty times a day, and an input on it is an input that gets
 * changed by a thumb aiming for the tab strip.
 */
export function ServiceFacts({service}: {service: ServiceView}) {
  return (
    <>
      <Card
        title={service.site ? 'What it publishes' : 'What it runs'}
        action={<Badge tone="accent">{kindLabel(service.kind)}</Badge>}
      >
        <CardFacts>
          {service.app ? (
            <>
              <CardFact label="Image">
                {service.image ? (
                  <code className="font-mono text-xs break-all">{service.image}</code>
                ) : (
                  'Not set'
                )}
              </CardFact>

              {service.imageDigest ? (
                <CardFact label="Digest the node pulled">
                  <span className="flex items-center gap-2">
                    <code className="font-mono text-xs break-all">
                      {shorten(service.imageDigest)}
                    </code>
                    <CopyButton
                      value={service.imageDigest}
                      size="sm"
                      describedAs="Copy the image digest"
                    />
                  </span>
                </CardFact>
              ) : null}

              <CardFact label="Command">
                {service.command ? (
                  <code className="font-mono text-xs break-all">{service.command}</code>
                ) : (
                  "The image's own CMD"
                )}
              </CardFact>

              {service.entrypoint ? (
                <CardFact label="Entrypoint">
                  <code className="font-mono text-xs break-all">{service.entrypoint}</code>
                </CardFact>
              ) : null}

              <CardFact label="Working directory">
                {service.workingDir ? (
                  <code className="font-mono text-xs break-all">{service.workingDir}</code>
                ) : (
                  "The image's own"
                )}
              </CardFact>

              <CardFact label="Port">
                {service.containerPort ?? 'None - nothing can route to it yet'}
              </CardFact>

              <CardFact label="Health check">
                {service.healthCheckPath
                  ? `${service.healthCheckPath} every ${service.healthCheckIntervalSeconds}s`
                  : 'None'}
              </CardFact>

              <CardFact label="Restart">{restartPolicyLabel(service.restartPolicy)}</CardFact>
            </>
          ) : (
            <>
              <CardFact label="Build">
                {service.buildPreset ? presetLabel(service.buildPreset) : 'Not set'}
              </CardFact>

              <CardFact label="Build command">
                {service.buildCommand ? (
                  <code className="font-mono text-xs break-all">{service.buildCommand}</code>
                ) : (
                  "The preset's own"
                )}
              </CardFact>

              <CardFact label="Output directory">
                <code className="font-mono text-xs break-all">
                  {service.buildOutputDir || '.'}
                </code>
              </CardFact>

              <CardFact label="Releases kept">
                {service.keepReleases} - a rollback is a symlink swap, not a rebuild
              </CardFact>
            </>
          )}

          <CardFact label="Repository">
            {service.repositoryUrl ? (
              <span className="flex flex-col gap-0.5">
                <code className="font-mono text-xs break-all">{service.repositoryUrl}</code>
                <span className="text-xs text-ink-500 dark:text-ink-400">
                  {service.repositoryBranch || 'default branch'}
                  {service.hasRepositoryCredential ? ' · deploy key stored' : ''}
                  {service.autoDeploy ? ' · deploys on push' : ' · deploys when you ask'}
                </span>
              </span>
            ) : (
              'None. Deploy by uploading an archive, or add one on Settings.'
            )}
          </CardFact>

          <CardFact label="Created">
            <RelativeTime at={service.createdAt} />
          </CardFact>
        </CardFacts>
      </Card>

      <Card
        title="Limits and isolation"
        description="Enforced on the node by cgroups, and counted against your plan."
      >
        <CardFacts>
          <CardFact label="CPU">
            {service.cpuMillicores} millicores · {formatCores(service.cpuMillicores)}
          </CardFact>
          <CardFact label="Memory">
            <ByteSize bytes={service.memoryBytes} />
          </CardFact>
          <CardFact label="Disk (the container's own layer)">
            <ByteSize bytes={service.diskBytes} />
          </CardFact>
          <CardFact label="Processes">{service.pidsLimit}</CardFact>
          <CardFact label="Isolation">{isolationLabel(service.runtimeIsolation)}</CardFact>
          <CardFact label="Placement tags">
            {service.requiredTags.length > 0 ? service.requiredTags.join(', ') : 'Any node'}
          </CardFact>
        </CardFacts>
      </Card>
    </>
  )
}

/** `sha256:9f86d0…f4b1` - enough to compare two by eye, short enough for a phone row. */
function shorten(digest: string): string {
  return digest.length <= 24 ? digest : `${digest.slice(0, 14)}…${digest.slice(-6)}`
}
