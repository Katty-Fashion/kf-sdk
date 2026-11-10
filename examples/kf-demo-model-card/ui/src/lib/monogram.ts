/**
 * The card's monogram, in place of a picture: the initials of the first two words of the model's name, else the first
 * two letters or digits of its style number, else `?`; on a circle whose color follows the model's id (the same model
 * keeps its color on every page).
 */

const LETTER_OR_DIGIT = /[\p{L}\p{N}]/u;

export function monogram(name: string | null | undefined, styleNumber: string | null | undefined): string {
  const words = (name ?? '').trim().split(/\s+/).filter(Boolean);
  if (words.length > 0) {
    return words
      .slice(0, 2)
      .map((word) => Array.from(word)[0])
      .join('')
      .toLocaleUpperCase();
  }
  const chars = Array.from(styleNumber ?? '').filter((c) => LETTER_OR_DIGIT.test(c));
  if (chars.length > 0) return chars.slice(0, 2).join('').toLocaleUpperCase();
  return '?';
}

/** A CSS color for the monogram's circle: a hue chosen by the id, a neutral grey without one. */
export function monogramColor(id: number | null | undefined): string {
  if (id === null || id === undefined || !Number.isFinite(id)) return 'hsl(220 9% 46%)';
  const hue = (((Math.trunc(id) * 137) % 360) + 360) % 360;
  return `hsl(${hue} 55% 42%)`;
}
