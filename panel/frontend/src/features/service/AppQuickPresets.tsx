import {t} from '@/i18n'
import {Badge, cx} from '@/shell'
import type {FormFields} from '@/shell'

import type {ServiceFormValues} from './serviceFormValues'

export interface QuickPreset {
  id: string
  label: string
  image: string
  command?: string
  port?: string
  workingDir?: string
  badge: string
}

export const APP_QUICK_PRESETS: QuickPreset[] = [
  {
    id: 'ubuntu',
    label: 'Ubuntu Linux Sandbox',
    image: 'ubuntu:24.04',
    command: 'sleep infinity',
    workingDir: '/root',
    badge: 'Linux VPS',
  },
  {
    id: 'debian',
    label: 'Debian Linux Sandbox',
    image: 'debian:12-slim',
    command: 'sleep infinity',
    workingDir: '/root',
    badge: 'Linux VPS',
  },
  {
    id: 'alpine',
    label: 'Alpine Linux (Minimal)',
    image: 'alpine:3.20',
    command: 'sleep infinity',
    workingDir: '/root',
    badge: 'Lightweight',
  },
  {
    id: 'nginx',
    label: 'Nginx Web Server',
    image: 'nginx:alpine',
    port: '80',
    workingDir: '/usr/share/nginx/html',
    badge: 'Web App',
  },
  {
    id: 'node',
    label: 'Node.js 22 Runtime',
    image: 'node:22-alpine',
    command: 'node server.js',
    port: '3000',
    workingDir: '/app',
    badge: 'Web App',
  },
  {
    id: 'python',
    label: 'Python 3.12 Runtime',
    image: 'python:3.12-slim',
    command: 'python -m http.server 8000',
    port: '8000',
    workingDir: '/app',
    badge: 'Web App',
  },
]

export function AppQuickPresets({
  form,
  disabled,
}: {
  form: FormFields<ServiceFormValues>
  disabled?: boolean
}) {
  function applyPreset(preset: QuickPreset) {
    if (disabled) return
    form.patch({
      image: preset.image,
      command: preset.command ?? '',
      containerPort: preset.port ?? form.data.containerPort,
      workingDir: preset.workingDir ?? form.data.workingDir,
    })
  }

  return (
    <div className="flex flex-col gap-2">
      <span className="text-xs font-medium uppercase tracking-wide text-ink-500 dark:text-ink-400">
        {t('service.runtime.presets_label')}
      </span>
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
        {APP_QUICK_PRESETS.map((preset) => {
          const isSelected = form.data.image === preset.image
          return (
            <button
              key={preset.id}
              type="button"
              disabled={disabled}
              onClick={() => applyPreset(preset)}
              className={cx(
                'flex flex-col items-start gap-1 rounded-lg border p-2.5 text-left transition-all disabled:cursor-not-allowed disabled:opacity-60',
                isSelected
                  ? 'border-accent-500 bg-accent-500/10'
                  : 'border-ink-200 bg-white hover:border-ink-300 dark:border-ink-800 dark:bg-ink-900 dark:hover:border-ink-700',
              )}
            >
              <div className="flex w-full items-center justify-between gap-1">
                <span className="truncate text-xs font-semibold text-ink-900 dark:text-ink-100">
                  {preset.label}
                </span>
                <Badge>{preset.badge}</Badge>
              </div>
              <span className="truncate font-mono text-[11px] text-ink-500 dark:text-ink-400">
                {preset.image}
              </span>
            </button>
          )
        })}
      </div>
    </div>
  )
}
