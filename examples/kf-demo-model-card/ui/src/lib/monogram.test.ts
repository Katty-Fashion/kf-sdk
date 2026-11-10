import { describe, expect, it } from 'vitest';
import { monogram, monogramColor } from './monogram';

describe('monogram', () => {
  it('takes the initials of the first two words of the name', () => {
    expect(monogram('summer linen dress', 'SN-1')).toBe('SL');
    expect(monogram('  Ștefania   top ', null)).toBe('ȘT');
  });

  it('takes one initial from a one-word name', () => {
    expect(monogram('Dress', 'SN-1')).toBe('D');
  });

  it('falls back to the first two letters or digits of the style number', () => {
    expect(monogram(null, 'kf-2041')).toBe('KF');
    expect(monogram('   ', '-9a')).toBe('9A');
  });

  it('is ? without a name and a style number', () => {
    expect(monogram(null, null)).toBe('?');
    expect(monogram('', '--')).toBe('?');
  });
});

describe('monogramColor', () => {
  it('is the same color for the same id and another for another id', () => {
    expect(monogramColor(42)).toBe(monogramColor(42));
    expect(monogramColor(42)).not.toBe(monogramColor(43));
    expect(monogramColor(42)).toMatch(/^hsl\(\d+ /);
  });

  it('is a neutral color without an id', () => {
    expect(monogramColor(null)).toBe(monogramColor(null));
    expect(monogramColor(null)).not.toBe(monogramColor(0));
  });
});
