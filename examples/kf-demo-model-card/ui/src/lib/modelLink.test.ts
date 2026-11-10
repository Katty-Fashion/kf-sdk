import { describe, expect, it } from 'vitest';
import { modelLink, qrLink } from './modelLink';

describe('modelLink', () => {
  it('is the model page on the given origin', () => {
    expect(modelLink('https://kf.example.ro', 42)).toBe('https://kf.example.ro/models/42');
  });

  it('carries the selected version', () => {
    expect(modelLink('https://kf.example.ro', 42, 7)).toBe('https://kf.example.ro/models/42?version=7');
  });

  it('ignores an absent version', () => {
    expect(modelLink('https://kf.example.ro', 42, null)).toBe('https://kf.example.ro/models/42');
    expect(modelLink('https://kf.example.ro', 42, undefined)).toBe('https://kf.example.ro/models/42');
  });

  it('drops a trailing slash of the origin', () => {
    expect(modelLink('http://localhost:3561/', 5)).toBe('http://localhost:3561/models/5');
  });

  it('reads window.location.origin by default', () => {
    expect(modelLink(undefined, 3)).toBe(`${window.location.origin}/models/3`);
  });
});

describe('qrLink', () => {
  it('prefers the host href (ctx.href), absolute as given and a path on the window origin', () => {
    expect(qrLink('https://kf.exemplu.ro/customer/models/42', 42, 7)).toBe('https://kf.exemplu.ro/customer/models/42');
    expect(qrLink('/models/42?version=7', 42, 7)).toBe(`${window.location.origin}/models/42?version=7`);
  });

  it('builds the link itself without an href', () => {
    expect(qrLink(null, 42, 7)).toBe(`${window.location.origin}/models/42?version=7`);
    expect(qrLink('  ', 42, null)).toBe(`${window.location.origin}/models/42`);
  });
});
