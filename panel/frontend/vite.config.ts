import {defineConfig, type Plugin} from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import {mkdirSync, rmSync, writeFileSync} from 'node:fs'
import {resolve} from 'node:path'
import {fileURLToPath} from 'node:url'

// This file is ESM, so __dirname does not exist; import.meta gives the same thing.
const here = fileURLToPath(new URL('.', import.meta.url))

const OUT_DIR = resolve(here, '../build/frontend')
const HOT_FILE = resolve(here, '../build/frontend-dev/hot')
const DEV_PORT = 5173
const DEV_HOST = '127.0.0.1'

/**
 * Tells the Spring side that a dev server is running, and where.
 *
 * The alternative is a flag a developer has to remember to set and then remember to
 * unset, which is how you end up staring at a stale bundle wondering why nothing
 * changes. This file exists exactly as long as the dev server does: written when it
 * starts, removed when it stops. `vite build` deliberately does not remove it - a build
 * does not stop a running dev server, so claiming otherwise would be a lie. The one case
 * left is a dev server killed hard enough to skip its own cleanup, and the Spring proxy
 * answers that with a message naming the file to delete.
 *
 * The address is written as an IP, not as "localhost". The server binds 127.0.0.1, and
 * on Windows a client resolving "localhost" tries ::1 first and gets a refused
 * connection - which looks exactly like the dev server not running.
 */
function hotFile(): Plugin {
  return {
    name: 'wisper-hot-file',
    configureServer(server) {
      const address = server.config.server.host
      const host = typeof address === 'string' ? address : DEV_HOST
      const origin = `http://${host}:${server.config.server.port ?? DEV_PORT}`
      mkdirSync(resolve(HOT_FILE, '..'), {recursive: true})
      writeFileSync(HOT_FILE, origin, 'utf8')

      const clean = () => rmSync(HOT_FILE, {force: true})
      server.httpServer?.once('close', clean)
      for (const signal of ['SIGINT', 'SIGTERM'] as const) {
        process.once(signal, () => {
          clean()
          process.exit()
        })
      }
    },
  }
}

/*
 * Builds the client into panel/build/frontend, which the Gradle `frontend` task copies
 * into the jar's static resources. Nothing generated is ever written under src/, so a
 * clean checkout has no build output in it.
 *
 * `base` matches the resource handler in WebMvcConfig: everything static is served
 * under /assets/, so no application route can shadow a file and no file can shadow a
 * route.
 */
export default defineConfig({
  plugins: [react(), tailwindcss(), hotFile()],
  base: '/assets/app/',

  /*
   * The browser never talks to this server directly. Spring proxies /assets/app/** to it
   * while the hot file exists, so the whole application is one origin on one port - no
   * CORS, no second address to remember, and cookies behave the way they do in
   * production.
   *
   * Vite's own middleware mode is for a Node host embedding it; the backend here is a
   * JVM, so proxying is the equivalent.
   */
  server: {
    port: DEV_PORT,
    strictPort: true,
    // Loopback only: the proxy is the only client, and a dev server that serves the
    // whole source tree has no business being reachable from the network.
    host: DEV_HOST,
    /*
     * The one thing that cannot go through the servlet proxy. Upgrading a WebSocket
     * inside a Spring MVC filter means hand-writing a bridge, which is a lot of
     * dev-only machinery; the HMR socket connects straight to Vite instead. WebSockets
     * are not subject to CORS, so this needs no configuration on either side.
     */
    hmr: {protocol: 'ws', host: DEV_HOST, port: DEV_PORT},
  },

  build: {
    outDir: OUT_DIR,
    emptyOutDir: true,
    // The Java shell reads this to find the hashed entry name, and uses that name as
    // Inertia's asset version.
    manifest: true,
    // The terminal and the editor are both large and neither is on the first screen a
    // customer sees, so they are split out by the dynamic imports in their features.
    chunkSizeWarningLimit: 900,
    sourcemap: false,
  },

  resolve: {
    alias: {
      '@': resolve(here, 'src'),
    },
  },
})
