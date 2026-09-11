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
 */

const ENGINES: Record<EngineKind, string> = {
  POSTGRES: 'PostgreSQL',
  MYSQL: 'MySQL',
}

/** Mirrors `EngineKind.uriScheme()`. Used to build the URI the customer copies. */
const SCHEMES: Record<EngineKind, string> = {
  POSTGRES: 'postgresql',
  MYSQL: 'mysql',
}

const MODES: Record<EngineMode, string> = {
  SHARED: 'Shared',
  DEDICATED: 'Dedicated',
}

const STATES: Record<ManagedDatabaseState, string> = {
  PENDING: 'Being created',
  READY: 'Ready',
  SUSPENDED: 'Suspended',
  FAILED: 'Failed',
  DELETING: 'Being dropped',
}

const STATE_TONES: Record<ManagedDatabaseState, BadgeTone> = {
  PENDING: 'neutral',
  READY: 'running',
  SUSPENDED: 'degraded',
  FAILED: 'failed',
  DELETING: 'neutral',
}

export function engineLabel(engine: EngineKind): string {
  return ENGINES[engine]
}

export function engineScheme(engine: EngineKind): string {
  return SCHEMES[engine]
}

export function modeLabel(mode: EngineMode): string {
  return MODES[mode]
}

export function databaseStateLabel(state: ManagedDatabaseState): string {
  return STATES[state]
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
    return 'Being created. The node picks the change up on its next reconcile, and the connection details appear as soon as it confirms.'
  }
  if (database.state === 'DELETING') {
    return 'Being dropped. The node removes what is not in its spec, so this finishes even if it is offline right now.'
  }
  if (database.state === 'FAILED') {
    return database.lastError ?? 'The node could not create this database and did not say why.'
  }
  if (database.state === 'SUSPENDED') {
    return 'Suspended. It still exists and still holds its data; nothing can connect to it until it is resumed.'
  }
  if (database.overQuota) {
    return 'Over its size limit. Writes may start failing - raise the limit or free space in it.'
  }
  if (!database.nodeReachable) {
    return 'Serving normally. The panel has no control stream to its node at the moment, so rotating the password and dropping it are unavailable until that comes back.'
  }
  if (database.usedBytes === null) {
    return 'Ready. The node has not measured its size yet, which it does on its own schedule.'
  }
  return 'Ready.'
}

/** The same, for one engine container on the admin screen. */
export function engineSentence(engine: DatabaseEngineView): string {
  if (engine.lastError) {
    return engine.lastError
  }
  if (engine.reportedState === null) {
    return 'The node has not reported on this container yet. That is a new engine, not a stopped one.'
  }
  if (!engine.converged) {
    return `Asked to be ${engine.desiredState.toLowerCase()}, and the node reports ${engine.reportedState.toLowerCase()}. It reconciles every fifteen seconds.`
  }
  if (!engine.nodeReachable) {
    return 'Running. The panel has no control stream to its node, so nothing can be published to it right now.'
  }
  return engine.desiredState === 'RUNNING'
    ? `Running and holding ${engine.databaseCount} customer ${engine.databaseCount === 1 ? 'database' : 'databases'}.`
    : 'Stopped, with its data directory left where it is.'
}
