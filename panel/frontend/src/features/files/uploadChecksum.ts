/**
 * The SHA-256 of one chunk, as the hex the panel forwards to the node.
 *
 * Per chunk rather than per file, and that is the whole reason this is cheap enough to do
 * on a phone: `crypto.subtle.digest` has no streaming form, so hashing a 3 GB file would
 * mean holding 3 GB in memory before the first byte was sent. A chunk is eight megabytes,
 * which is already in memory because it is about to be uploaded.
 *
 * What it buys is the check mobile networks actually need. A dropped and retried request
 * on a flaky 4G connection is the ordinary case here, and a chunk that arrived corrupted
 * is caught at the node instead of ending up as eight megabytes of garbage in the middle
 * of the customer's file.
 *
 * `crypto.subtle` exists on every browser in a secure context. It is absent over plain
 * HTTP on a non-loopback host, which is a way somebody will run this panel behind a
 * tunnel they terminate themselves - so its absence returns null and the upload carries
 * on without the per-chunk check, rather than refusing to upload anything at all.
 */

const HEX = '0123456789abcdef'

export async function sha256Hex(chunk: Blob): Promise<string | null> {
  const subtle = globalThis.crypto?.subtle
  if (!subtle) {
    return null
  }
  try {
    const digest = await subtle.digest('SHA-256', await chunk.arrayBuffer())
    return hex(new Uint8Array(digest))
  } catch {
    // A digest that throws is a browser that will not do this, not a broken upload.
    return null
  }
}

function hex(bytes: Uint8Array): string {
  let out = ''
  for (const byte of bytes) {
    out += HEX[(byte >> 4) & 0xf]
    out += HEX[byte & 0xf]
  }
  return out
}
