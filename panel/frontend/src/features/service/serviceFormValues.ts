import type {ServiceDraft, ServiceView} from './serviceTypes'

/**
 * The fields a service form submits, as the multipart body actually carries them.
 *
 * One shape for both writes - `POST /projects/{id}/services` and
 * `POST /services/{id}/settings` - because they describe the same service and their Java
 * records differ by three components. Spring's constructor binding ignores a parameter its
 * record has no component for, so the new-service form may carry `healthCheckIntervalSeconds`
 * and the settings form may carry `kind` and `slug`; neither reaches a setter, and the
 * alternative is two nearly identical field-group components maintained in parallel.
 *
 * Everything is a string because that is what an `<input>` holds and what multipart sends.
 * A blank string is "not given": `ServiceDraft`'s compact constructor turns it into null
 * and then into the default, which is exactly what an untouched box should mean.
 *
 * A type alias rather than an interface on purpose - `useFormFields<T extends FormValues>`
 * needs the implicit index signature only an alias gets.
 */
export type ServiceFormValues = {
  name: string
  slug: string
  kind: string

  image: string
  command: string
  entrypoint: string
  workingDir: string
  containerPort: string
  healthCheckPath: string
  healthCheckIntervalSeconds: string
  restartPolicy: string

  buildPreset: string
  buildCommand: string
  buildOutputDir: string
  keepReleases: string

  repositoryUrl: string
  repositoryBranch: string
  repositoryCredential: string
  autoDeploy: boolean

  cpuMillicores: string
  memoryBytes: string
  diskBytes: string
  pidsLimit: string
  requiredTags: string

  runtimeIsolation: string
  isolationReason: string
}

/** The new-service form, seeded from the draft the use-case would have applied itself. */
export function valuesFromDraft(draft: ServiceDraft): ServiceFormValues {
  return {
    name: draft.name ?? '',
    slug: draft.slug ?? '',
    kind: draft.kind ?? '',

    image: draft.image ?? '',
    command: draft.command.join(' '),
    entrypoint: draft.entrypoint.join(' '),
    workingDir: draft.workingDir ?? '',
    containerPort: draft.containerPort == null ? '' : String(draft.containerPort),
    healthCheckPath: draft.healthCheckPath ?? '',
    healthCheckIntervalSeconds: String(draft.healthCheckIntervalSeconds),
    restartPolicy: draft.restartPolicy,

    buildPreset: draft.buildPreset ?? '',
    buildCommand: draft.buildCommand ?? '',
    buildOutputDir: draft.buildOutputDir ?? '',
    keepReleases: String(draft.keepReleases),

    repositoryUrl: draft.repositoryUrl ?? '',
    repositoryBranch: draft.repositoryBranch ?? '',
    repositoryCredential: '',
    autoDeploy: draft.autoDeploy,

    cpuMillicores: String(draft.cpuMillicores),
    memoryBytes: String(draft.memoryBytes),
    diskBytes: String(draft.diskBytes),
    pidsLimit: String(draft.pidsLimit),
    requiredTags: draft.requiredTags.join(', '),

    runtimeIsolation: draft.runtimeIsolation,
    isolationReason: draft.isolationReason ?? '',
  }
}

/**
 * The settings form, seeded from the service as it stands.
 *
 * `repositoryCredential` starts empty even when one is stored: it is the only box in the
 * panel where blank means "leave it alone", and `hasRepositoryCredential` is what lets the
 * form say so rather than implying there is nothing there.
 */
export function valuesFromService(service: ServiceView): ServiceFormValues {
  return {
    name: service.name,
    slug: service.slug,
    kind: service.kind,

    image: service.image ?? '',
    command: service.command ?? '',
    entrypoint: service.entrypoint ?? '',
    workingDir: service.workingDir ?? '',
    containerPort: service.containerPort == null ? '' : String(service.containerPort),
    healthCheckPath: service.healthCheckPath ?? '',
    healthCheckIntervalSeconds: String(service.healthCheckIntervalSeconds),
    restartPolicy: service.restartPolicy,

    buildPreset: service.buildPreset ?? '',
    buildCommand: service.buildCommand ?? '',
    buildOutputDir: service.buildOutputDir ?? '',
    keepReleases: String(service.keepReleases),

    repositoryUrl: service.repositoryUrl ?? '',
    repositoryBranch: service.repositoryBranch ?? '',
    repositoryCredential: '',
    autoDeploy: service.autoDeploy,

    cpuMillicores: String(service.cpuMillicores),
    memoryBytes: String(service.memoryBytes),
    diskBytes: String(service.diskBytes),
    pidsLimit: String(service.pidsLimit),
    requiredTags: service.requiredTags.join(', '),

    runtimeIsolation: service.runtimeIsolation,
    isolationReason: service.isolationReason ?? '',
  }
}
