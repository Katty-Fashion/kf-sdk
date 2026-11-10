import { describe, expect, it } from 'vitest';
import { REFRESH_MARGIN_MS, MIN_REFRESH_MS, earliestExpiry, refreshDelay } from './imageExpiry';

const NOW = Date.parse('2026-11-02T10:00:00Z');

describe('refreshDelay', () => {
  it('re-reads the image 30 s before the presigned URL expires (10 minutes after the read)', () => {
    expect(REFRESH_MARGIN_MS).toBe(30_000);
    expect(refreshDelay('2026-11-02T10:10:00Z', NOW)).toBe(10 * 60_000 - 30_000);
  });

  it('never schedules sooner than the floor, even for a URL already expired', () => {
    expect(refreshDelay('2026-11-02T10:00:10Z', NOW)).toBe(MIN_REFRESH_MS);
    expect(refreshDelay('2026-11-02T09:00:00Z', NOW)).toBe(MIN_REFRESH_MS);
  });

  it('is null without an image, without an expiry or with an unreadable one', () => {
    expect(refreshDelay(null, NOW)).toBeNull();
    expect(refreshDelay(undefined, NOW)).toBeNull();
    expect(refreshDelay('not a date', NOW)).toBeNull();
  });
});

describe('earliestExpiry', () => {
  it('takes the first expiry among the cards with an image', () => {
    expect(
      earliestExpiry([
        { image: { url: 'a', expiresAt: '2026-11-02T10:10:00Z' } },
        { image: null },
        { image: { url: 'b', expiresAt: '2026-11-02T10:05:00Z' } },
        {},
      ]),
    ).toBe('2026-11-02T10:05:00Z');
  });

  it('is null when no card has an image', () => {
    expect(earliestExpiry([{ image: null }, {}])).toBeNull();
  });
});
