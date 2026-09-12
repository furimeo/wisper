import {t} from '@/i18n'
import {Badge, ByteSize, Card, CardFact, CardFacts, CopyButton, RelativeTime} from '@/shell'

import {formatCores} from '@/features/org/quotaVocabulary'

import type {ServiceView} from './serviceTypes'
import {isolationLabel, kindLabel, presetLabel, restartPolicyLabel} from './serviceVocabulary'

export function ServiceFacts({service}: {service: ServiceView}) {
  return (
    <>
      <Card
        title={service.site ? t('service.facts.what_it_publishes') : t('service.facts.what_it_runs')}
        action={<Badge tone="accent">{kindLabel(service.kind)}</Badge>}
      >
        <CardFacts>
          {service.app ? (
            <>
              <CardFact label={t('service.facts.image')}>
                {service.image ? (
                  <code className="font-mono text-xs break-all">{service.image}</code>
                ) : (
                  t('service.facts.not_set')
                )}
              </CardFact>

              {service.imageDigest ? (
                <CardFact label={t('service.facts.digest_pulled')}>
                  <span className="flex items-center gap-2">
                    <code className="font-mono text-xs break-all">
                      {shorten(service.imageDigest)}
                    </code>
                    <CopyButton
                      value={service.imageDigest}
                      size="sm"
                      describedAs={t('service.facts.copy_digest')}
                    />
                  </span>
                </CardFact>
              ) : null}

              <CardFact label={t('service.facts.command')}>
                {service.command ? (
                  <code className="font-mono text-xs break-all">{service.command}</code>
                ) : (
                  t('service.facts.image_cmd')
                )}
              </CardFact>

              {service.entrypoint ? (
                <CardFact label={t('service.facts.entrypoint')}>
                  <code className="font-mono text-xs break-all">{service.entrypoint}</code>
                </CardFact>
              ) : null}

              <CardFact label={t('service.facts.working_dir')}>
                {service.workingDir ? (
                  <code className="font-mono text-xs break-all">{service.workingDir}</code>
                ) : (
                  t('service.facts.image_working_dir')
                )}
              </CardFact>

              <CardFact label={t('service.facts.port')}>
                {service.containerPort ?? t('service.facts.port_none')}
              </CardFact>

              <CardFact label={t('service.facts.health_check')}>
                {service.healthCheckPath
                  ? t('service.facts.health_check_desc', {
                      path: service.healthCheckPath,
                      interval: service.healthCheckIntervalSeconds,
                    })
                  : t('service.facts.none')}
              </CardFact>

              <CardFact label={t('service.facts.restart')}>{restartPolicyLabel(service.restartPolicy)}</CardFact>
            </>
          ) : (
            <>
              <CardFact label={t('service.facts.build')}>
                {service.buildPreset ? presetLabel(service.buildPreset) : t('service.facts.not_set')}
              </CardFact>

              <CardFact label={t('service.facts.build_command')}>
                {service.buildCommand ? (
                  <code className="font-mono text-xs break-all">{service.buildCommand}</code>
                ) : (
                  t('service.facts.preset_own')
                )}
              </CardFact>

              <CardFact label={t('service.facts.output_dir')}>
                <code className="font-mono text-xs break-all">
                  {service.buildOutputDir || '.'}
                </code>
              </CardFact>

              <CardFact label={t('service.facts.releases_kept')}>
                {t('service.facts.releases_kept_hint', {count: service.keepReleases})}
              </CardFact>
            </>
          )}

          <CardFact label={t('service.facts.repository')}>
            {service.repositoryUrl ? (
              <span className="flex flex-col gap-0.5">
                <code className="font-mono text-xs break-all">{service.repositoryUrl}</code>
                <span className="text-xs text-ink-500 dark:text-ink-400">
                  {service.repositoryBranch || t('service.facts.default_branch')}
                  {service.hasRepositoryCredential ? t('service.facts.deploy_key_stored') : ''}
                  {service.autoDeploy ? t('service.facts.deploys_on_push') : t('service.facts.deploys_when_asked')}
                </span>
              </span>
            ) : (
              t('service.facts.no_repository')
            )}
          </CardFact>

          <CardFact label={t('service.facts.created')}>
            <RelativeTime at={service.createdAt} />
          </CardFact>
        </CardFacts>
      </Card>

      <Card
        title={t('service.facts.limits_and_isolation')}
        description={t('service.facts.limits_description')}
      >
        <CardFacts>
          <CardFact label={t('service.facts.cpu')}>
            {t('service.facts.cpu_millicores', {
              millicores: service.cpuMillicores,
              cores: formatCores(service.cpuMillicores),
            })}
          </CardFact>
          <CardFact label={t('service.facts.memory')}>
            <ByteSize bytes={service.memoryBytes} />
          </CardFact>
          <CardFact label={t('service.facts.disk')}>
            <ByteSize bytes={service.diskBytes} />
          </CardFact>
          <CardFact label={t('service.facts.processes')}>{service.pidsLimit}</CardFact>
          <CardFact label={t('service.facts.isolation')}>{isolationLabel(service.runtimeIsolation)}</CardFact>
          <CardFact label={t('service.facts.placement_tags')}>
            {service.requiredTags.length > 0 ? service.requiredTags.join(', ') : t('service.facts.any_node')}
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
