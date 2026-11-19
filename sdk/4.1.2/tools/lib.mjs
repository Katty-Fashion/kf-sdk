// Shared helpers of the SDK tools. Node >= 22, only node: modules.
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/** The root of the repository that holds the SDK (two directories above tools/). */
export const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');

/** Text with LF line endings: the copies and their hashes do not depend on core.autocrlf. */
export function lf(text) {
  return text.replace(/\r\n?/g, '\n');
}

export function readText(path) {
  return lf(readFileSync(path, 'utf8'));
}

export function sha256(text) {
  return createHash('sha256').update(lf(text), 'utf8').digest('hex');
}

/** `--name value` and `--flag` arguments. */
export function parseArgs(argv, known) {
  const args = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith('--')) throw new Error(`unexpected argument ${a}`);
    const name = a.slice(2);
    if (!(name in known)) throw new Error(`unknown argument ${a}`);
    if (known[name] === 'flag') args[name] = true;
    else args[name] = argv[++i];
  }
  return args;
}

export function isMain(meta) {
  return process.argv[1] != null && resolve(process.argv[1]) === fileURLToPath(meta.url);
}
