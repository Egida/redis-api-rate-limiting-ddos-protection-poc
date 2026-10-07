import { describe, expect, it } from 'vitest';

import { clampRequestCount, DEFAULT_REQUEST_COUNT, MAX_REQUEST_COUNT, formatWindow } from './demo-catalog';

describe('demo catalog', () => {
  it('caps the request count', () => {
    expect(clampRequestCount(20)).toBe(20);
    expect(clampRequestCount('20')).toBe(20);
    expect(clampRequestCount(0)).toBe(1);
    expect(clampRequestCount(-10)).toBe(1);
    expect(clampRequestCount('abc')).toBe(1);
    expect(clampRequestCount(MAX_REQUEST_COUNT + 500)).toBe(MAX_REQUEST_COUNT);
    expect(MAX_REQUEST_COUNT).toBe(150);
    expect(DEFAULT_REQUEST_COUNT).toBe(20);
  });

  it('formats windows in plain language', () => {
    expect(formatWindow(60)).toBe('1 minute');
    expect(formatWindow(120)).toBe('2 minutes');
    expect(formatWindow(30)).toBe('30 s');
  });
});
