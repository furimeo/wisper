import {t} from '@/i18n'
import {Tabs} from '@/shell'

/**
 * The two screens a project has: what is in it, and what can be done to it.
 *
 * Archive, restore and delete are on the second one rather than beside "add a service",
 * which is the whole reason there are two. Three destructive buttons on the screen a
 * customer opens forty times a day is three buttons that get pressed by a thumb aiming for
 * something else.
 */
export function ProjectTabs({projectId}: {projectId: string}) {
  return (
    <Tabs
      label="Project sections"
      items={[
        {href: `/projects/${projectId}`, label: t('project.tabs.overview')},
        {href: `/projects/${projectId}/settings`, label: t('project.tabs.settings')},
      ]}
    />
  )
}
