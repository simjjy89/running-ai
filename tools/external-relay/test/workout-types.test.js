'use strict';

// Characterization tests for workout-types.js, locked in unchanged ahead of the
// C:\running-ai\watchface-relay -> tools/external-relay migration (Phase 6I-1).
// Pure functions, no I/O - requires the module from its real relocated path so
// this suite always exercises exactly the committed file, not a copy.

const test = require('node:test');
const assert = require('node:assert/strict');
const {
  classifyTypeFromName,
  extractIntervalMetric,
  extractDistanceMetric,
  minutesMetric,
  labelForEvent,
  sanitizeLabel,
  TODAY_TEXT_MAX,
} = require('../workout-types');

test('classifyTypeFromName matches Korean and English keywords', () => {
  assert.equal(classifyTypeFromName('이지런 5km'), 'EASY');
  assert.equal(classifyTypeFromName('easy run'), 'EASY');
  assert.equal(classifyTypeFromName('인터벌 6x800m'), 'INTRVL');
  assert.equal(classifyTypeFromName('Tempo run'), 'TEMPO');
  assert.equal(classifyTypeFromName('휴식'), 'REST');
  assert.equal(classifyTypeFromName('something unrelated'), null);
});

test('extractIntervalMetric reads NxMm notation (the distance unit must be lowercase m - no /i flag)', () => {
  assert.equal(extractIntervalMetric('인터벌 6x800m'), '6X800M');
  assert.equal(extractIntervalMetric('6×800m'), '6X800M');
  assert.equal(extractIntervalMetric('6 x 800m'), '6X800M');
  // Existing quirk, preserved as-is: an uppercase "M" unit does not match (regex has no /i flag).
  assert.equal(extractIntervalMetric('6 X 800M'), null);
  assert.equal(extractIntervalMetric('no interval here'), null);
});

test('extractDistanceMetric rounds whole km, matching the WEEK-row convention', () => {
  assert.equal(extractDistanceMetric('장거리 8.19km'), '8KM');
  assert.equal(extractDistanceMetric('8 km'), '8KM');
  assert.equal(extractDistanceMetric('0km'), null);
  assert.equal(extractDistanceMetric('no distance here'), null);
});

test('minutesMetric rounds seconds to whole minutes, rejects non-positive', () => {
  assert.equal(minutesMetric(3000), '50M');
  assert.equal(minutesMetric(0), null);
  assert.equal(minutesMetric(-10), null);
  assert.equal(minutesMetric('not a number'), null);
});

test('labelForEvent prefers interval metric, then distance, then minutes, then bare type', () => {
  assert.equal(labelForEvent({ name: '인터벌 6x800m', moving_time: 3000 }), 'INTRVL 6X800M');
  assert.equal(labelForEvent({ name: '장거리 8.19km', moving_time: 3000 }), 'LONG 8KM');
  assert.equal(labelForEvent({ name: '이지런', moving_time: 1800 }), 'EASY 30M');
  assert.equal(labelForEvent({ name: '이지런', moving_time: 0 }), 'EASY');
  assert.equal(labelForEvent({ name: 'unclassified text', moving_time: 1800 }), 'RUN 30M');
});

test('sanitizeLabel keeps only the pixel font character set and clamps to TODAY_TEXT_MAX', () => {
  assert.equal(TODAY_TEXT_MAX, 14);
  assert.equal(sanitizeLabel('intrvl 6x800m'), 'INTRVL 6X800M');
  assert.equal(sanitizeLabel('a'.repeat(20)), 'A'.repeat(14));
  assert.equal(sanitizeLabel('한글 EASY 30M'), ' EASY 30M');
});
