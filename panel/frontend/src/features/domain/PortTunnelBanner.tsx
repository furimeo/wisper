import {useI18n} from '@/i18n'
import {Button, Card, Icon} from '@/shell'

export function PortTunnelBanner({
  port,
  onSelectPort,
}: {
  port: number
  onSelectPort: (port: number) => void
}) {
  const {t} = useI18n()

  return (
    <Card className="border-accent-500/30 bg-accent-50/50 dark:bg-accent-950/20">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-start gap-3">
          <div className="rounded-lg bg-accent-100 p-2 text-accent-600 dark:bg-accent-900/40 dark:text-accent-400">
            <Icon name="external" className="size-5" />
          </div>
          <div>
            <h3 className="text-sm font-semibold text-ink-900 dark:text-ink-100">
              {t('domain.tunnel_banner.title', {port})}
            </h3>
            <p className="mt-0.5 text-sm text-ink-600 dark:text-ink-400">
              {t('domain.tunnel_banner.description', {port})}
            </p>
          </div>
        </div>
        <Button
          variant="secondary"
          size="sm"
          className="shrink-0 self-start sm:self-center"
          onClick={() => onSelectPort(port)}
        >
          {t('domain.tunnel_banner.action', {port})}
        </Button>
      </div>
    </Card>
  )
}
