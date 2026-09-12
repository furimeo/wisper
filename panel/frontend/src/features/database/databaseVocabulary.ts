import {t} from '@/i18n'
import type {BadgeTone} from '@/shell'

import type {
  ConnectionString,
  DatabaseEngineView,
  EngineKind,
  EngineMode,
  ManagedDatabaseState,
  ManagedDatabaseView,
} from './databaseTypes'

/**
 * What each database enum is called on screen, and how a connection string is assembled.
 *
 * `EngineKind.label()` and `uriScheme()` exist in Java and Jackson does not serialise
 * them - they are not bean-named - so the words and the schemes live here. They match the
 * Java strings on purpose: a flash message written by the server and a dropdown written
 * here end up in the same sentence often enough that two names for one engine is a support
 * ticket.
/** Mirrors `EngineKind.uriScheme()`. Used to build the URI the customer copies. */
const SCHEMES: Record<EngineKind, string> = {
  POSTGRES: 'postgresql',
  MYSQL: 'mysql',
}

const STATE_TONES: Record<ManagedDatabaseState, BadgeTone> = {
  PENDING: 'neutral',
  READY: 'running',
  SUSPENDED: 'degraded',
  FAILED: 'failed',
  DELETING: 'neutral',
}

export function engineLabel(engine: EngineKind): string {
  switch (engine) {
    case 'POSTGRES':
      return t('database.engine.postgres')
    case 'MYSQL':
      return t('database.engine.mysql')
  }
}

export function engineScheme(engine: EngineKind): string {
  return SCHEMES[engine]
}

export function modeLabel(mode: EngineMode): string {
  switch (mode) {
    case 'SHARED':
      return t('database.mode.shared')
    case 'DEDICATED':
      return t('database.mode.dedicated')
  }
}

export function databaseStateLabel(state: ManagedDatabaseState): string {
  switch (state) {
    case 'PENDING':
      return t('database.state.pending')
    case 'READY':
      return t('database.state.ready')
    case 'SUSPENDED':
      return t('database.state.suspended')
    case 'FAILED':
      return t('database.state.failed')
    case 'DELETING':
      return t('database.state.deleting')
  }
}

export function databaseStateTone(state: ManagedDatabaseState): BadgeTone {
  return STATE_TONES[state]
}

/** The URI an application's driver takes. Mirrors `ConnectionString.uri()`. */
export function connectionUri(connection: ConnectionString): string {
  const {engine, username, password, host, port, database} = connection
  return `${SCHEMES[engine]}://${encodeURIComponent(username)}:${encodeURIComponent(password)}@${host}:${port}/${database}`
}

/** The same thing with the password blanked, for anywhere it might linger. */
export function redactedUri(connection: ConnectionString): string {
  const {engine, username, host, port, database} = connection
  return `${SCHEMES[engine]}://${username}:********@${host}:${port}/${database}`
}

/** What a JVM application wants. Mirrors `ConnectionString.jdbcUrl()`. */
export function jdbcUrl(connection: ConnectionString): string {
  const {engine, host, port, database} = connection
  return `jdbc:${SCHEMES[engine]}://${host}:${port}/${database}`
}

/** The address with no credential in it, for a list row. Mirrors `.address()`. */
export function databaseAddress(database: ManagedDatabaseView): string {
  return `${database.host}:${database.port}/${database.name}`
}

/**
 * The one line that answers "is this database all right?".
 *
 * `usedBytes === null` is not zero and never renders as an empty bar: a database nobody
 * has measured yet and an empty database are different answers, and only one of them means
 * something is wrong.
 */
export function databaseSentence(database: ManagedDatabaseView): string {
  if (database.state === 'PENDING') {
    return t('database.sentence.pending')
  }
  if (database.state === 'DELETING') {
    return t('database.sentence.deleting')
  }
  if (database.state === 'FAILED') {
    return database.lastError ?? t('database.sentence.failedDefault')
  }
  if (database.state === 'SUSPENDED') {
    return t('database.sentence.suspended')
  }
  if (database.overQuota) {
    return t('database.sentence.overQuota')
  }
  if (!database.nodeReachable) {
    return t('database.sentence.nodeUnreachable')
  }
  if (database.usedBytes === null) {
    return t('database.sentence.unmeasured')
  }
  return t('database.sentence.ready')
}

/** The same, for one engine container on the admin screen. */
export function engineSentence(engine: DatabaseEngineView): string {
  if (engine.lastError) {
    return engine.lastError
  }
  if (engine.reportedState === null) {
    return t('database.engineSentence.unreported')
  }
  if (!engine.converged) {
    return t('database.engineSentence.notConverged', {
      desired: engine.desiredState.toLowerCase(),
      reported: engine.reportedState.toLowerCase(),
    })
  }
  if (!engine.nodeReachable) {
    return t('database.engineSentence.nodeUnreachable')
  }
  return engine.desiredState === 'RUNNING'
    ? t('database.engineSentence.running', {count: engine.databaseCount})
    : t('database.engineSentence.stopped')
}
