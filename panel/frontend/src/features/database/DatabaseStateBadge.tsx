import {t} from '@/i18n'
import {Badge} from '@/shell'

import type {DatabaseEngineView, ManagedDatabaseView} from './databaseTypes'
import {databaseStateLabel, databaseStateTone} from './databaseVocabulary'

/**
 * What a managed database is doing, as a pill.
 *
 * Over quota outranks the state, because a database that is `READY` and full is the one
 * about to start refusing writes, and "Ready" is not what the customer needs to read on
 * that row. Being unreachable does not: the node holding it keeps serving whatever the
 * panel can or cannot see, and colouring that red would teach people that red means
 * nothing.
 */
export function DatabaseStateBadge({database}: {database: ManagedDatabaseView}) {
  if (database.overQuota && database.state === 'READY') {
    return (
      <Badge tone="failed" dot>
        {t('database.badge.overLimit')}
      </Badge>
    )
  }
  return (
    <Badge tone={databaseStateTone(database.state)} dot pulse={database.inFlight}>
      {databaseStateLabel(database.state)}
    </Badge>
  )
}

/**
 * The same for an engine container: intent against report, never merged.
 *
 * A container the operator asked to run and the node reports as stopped is the whole
 * reason both halves exist, and one pill could not show it.
 */
export function EngineStateBadge({engine}: {engine: DatabaseEngineView}) {
  if (engine.reportedState === null) {
    return (
      <Badge tone="neutral" dot pulse={engine.desiredState === 'RUNNING'}>
        {t('database.badge.engineNotReported')}
      </Badge>
    )
  }
  if (!engine.converged) {
    return (
      <Badge tone="degraded" dot pulse>
        {t('database.badge.engineWanted', {
          reported: engine.reportedState.toLowerCase(),
          desired: engine.desiredState.toLowerCase(),
        })}
      </Badge>
    )
  }
  return (
    <Badge tone={engine.desiredState === 'RUNNING' ? 'running' : 'neutral'} dot>
      {engine.desiredState === 'RUNNING' ? t('database.badge.engineRunning') : t('database.badge.engineStopped')}
    </Badge>
  )
}
