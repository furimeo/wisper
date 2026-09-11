/**
 * A first password an operator can hand to somebody else.
 *
 * It exists because the alternative is an operator inventing a password for another
 * person, which in practice means the same one every time.
 *
 * Twenty characters from a 62-symbol alphabet: about 119 bits, comfortably past
 * `PasswordPolicy.MINIMUM_CHARACTERS` and well under BCrypt's 72-byte ceiling.
 *
 * The rejection loop keeps the distribution flat. Taking `value % 62` from a byte would
 * make the first eight symbols very slightly more likely than the rest, which costs
 * nothing to avoid and is the kind of shortcut that ends up copied into somewhere it
 * matters.
 */
const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'

const LENGTH = 20

export function generatePassword(): string {
  const limit = 256 - (256 % ALPHABET.length)
  const password: string[] = []
  const byte = new Uint8Array(1)
  while (password.length < LENGTH) {
    crypto.getRandomValues(byte)
    const value = byte[0]!
    if (value < limit) {
      password.push(ALPHABET.charAt(value % ALPHABET.length))
    }
  }
  return password.join('')
}
