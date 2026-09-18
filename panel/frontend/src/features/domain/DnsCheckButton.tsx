import {useState} from 'react'

import {useI18n} from '@/i18n'
import {Badge, Button, Icon} from '@/shell'

interface CheckResult {
  resolved: boolean
  nodeAddress: string | null
  addresses: string[]
}

export function DnsCheckButton({
  serviceId,
  domainId,
  nodeAddress,
}: {
  serviceId: string
  domainId: string
  nodeAddress: string | null
}) {
  const {t} = useI18n()
  const [checking, setChecking] = useState(false)
  const [result, setResult] = useState<CheckResult | null>(null)

  const check = async () => {
    setChecking(true)
    try {
      const res = await fetch(`/services/${serviceId}/domains/${domainId}/dns-check`, {
        headers: {Accept: 'application/json'},
      })
      if (res.ok) {
        const data = (await res.json()) as CheckResult
        setResult(data)
      }
    } catch {
      setResult({resolved: false, nodeAddress, addresses: []})
    } finally {
      setChecking(false)
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-2">
      <Button
        variant="ghost"
        size="sm"
        disabled={checking}
        onClick={() => void check()}
        icon={<Icon name="refresh" className={checking ? 'size-3.5 animate-spin' : 'size-3.5'} />}
      >
        {checking ? t('domain.dns_check.checking') : t('domain.dns_check.button')}
      </Button>

      {result !== null ? (
        result.resolved ? (
          <Badge tone="running" dot>
            {t('domain.dns_check.success', {address: result.nodeAddress ?? ''})}
          </Badge>
        ) : (
          <Badge tone="degraded" dot>
            {result.addresses.length > 0
              ? t('domain.dns_check.mismatch', {
                  found: result.addresses.join(', '),
                  expected: nodeAddress ?? '?',
                })
              : t('domain.dns_check.unresolved')}
          </Badge>
        )
      ) : null}
    </div>
  )
}
