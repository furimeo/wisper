import type {Catalog} from '../translate'

/**
 * The English catalogue: every `*.ts` beside this file, merged.
 *
 * <p>Collected with a glob rather than a list of imports so that adding a feature's
 * strings is dropping one file in this folder. A hand-maintained index would be a file
 * every translator has to remember to edit, and the failure when they forget is silent -
 * the screen renders raw keys and nothing says why.
 *
 * <p>Eager, because this module is already behind the dynamic import in `catalog.ts`:
 * splitting each feature's strings out again would be a request per screen for a few
 * kilobytes of text.
 */
const modules = import.meta.glob<{messages: Catalog}>('./*.ts', {eager: true})

export const catalog: Catalog = Object.entries(modules)
  .filter(([path]) => !path.endsWith('/index.ts'))
  .reduce<Catalog>((all, [, module]) => Object.assign(all, module.messages), {})
