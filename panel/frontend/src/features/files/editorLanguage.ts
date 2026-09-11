import {css} from '@codemirror/lang-css'
import {html} from '@codemirror/lang-html'
import {javascript} from '@codemirror/lang-javascript'
import {json} from '@codemirror/lang-json'
import {python} from '@codemirror/lang-python'
import type {Extension} from '@uiw/react-codemirror'

import {extensionOf} from './fileKinds'

/**
 * Which CodeMirror language to open a file with, from its name.
 *
 * Five grammars, because five are what `package.json` declares and adding a sixth is
 * adding a dependency - which needs a reason better than "somebody might edit YAML".
 * Everything else opens as plain text, which is a real editing experience with
 * line numbers, search and undo, not a degraded one: the thing a customer actually needs
 * from this screen is to change one line in a config file and save it.
 *
 * The label is shown next to the file name so the highlighting is never a mystery. A
 * `.conf` opening as plain text is correct and saying so stops it reading as broken.
 */
export interface DetectedLanguage {
  label: string
  extensions: Extension[]
}

const PLAIN: DetectedLanguage = {label: 'Plain text', extensions: []}

/** Names with no extension that still have a grammar worth loading. */
const BY_NAME: Record<string, () => DetectedLanguage> = {
  '.babelrc': () => ({label: 'JSON', extensions: [json()]}),
  '.eslintrc': () => ({label: 'JSON', extensions: [json()]}),
  '.prettierrc': () => ({label: 'JSON', extensions: [json()]}),
}

const BY_EXTENSION: Record<string, () => DetectedLanguage> = {
  js: () => ({label: 'JavaScript', extensions: [javascript()]}),
  cjs: () => ({label: 'JavaScript', extensions: [javascript()]}),
  mjs: () => ({label: 'JavaScript', extensions: [javascript()]}),
  jsx: () => ({label: 'JavaScript (JSX)', extensions: [javascript({jsx: true})]}),
  ts: () => ({label: 'TypeScript', extensions: [javascript({typescript: true})]}),
  mts: () => ({label: 'TypeScript', extensions: [javascript({typescript: true})]}),
  cts: () => ({label: 'TypeScript', extensions: [javascript({typescript: true})]}),
  tsx: () => ({
    label: 'TypeScript (TSX)',
    extensions: [javascript({typescript: true, jsx: true})],
  }),
  json: () => ({label: 'JSON', extensions: [json()]}),
  jsonc: () => ({label: 'JSON', extensions: [json()]}),
  css: () => ({label: 'CSS', extensions: [css()]}),
  scss: () => ({label: 'SCSS (as CSS)', extensions: [css()]}),
  less: () => ({label: 'Less (as CSS)', extensions: [css()]}),
  html: () => ({label: 'HTML', extensions: [html()]}),
  htm: () => ({label: 'HTML', extensions: [html()]}),
  vue: () => ({label: 'Vue (as HTML)', extensions: [html()]}),
  svg: () => ({label: 'SVG (as HTML)', extensions: [html()]}),
  py: () => ({label: 'Python', extensions: [python()]}),
  pyi: () => ({label: 'Python', extensions: [python()]}),
}

export function languageFor(name: string): DetectedLanguage {
  const lower = name.toLowerCase()
  const byName = BY_NAME[lower]
  if (byName) {
    return byName()
  }
  const byExtension = BY_EXTENSION[extensionOf(lower)]
  return byExtension ? byExtension() : PLAIN
}
