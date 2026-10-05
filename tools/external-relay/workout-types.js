'use strict';

// Keyword -> short ASCII label used on the RUNNER ARCADE watch face TODAY row.
// The watch's pixel font only has digits, A-Z, space and ": + - / . % !" (see
// running-ai-watchface/source/PixelFont.mc) - no lower case, no Korean, no "x".
// Keys are matched against the Intervals.icu event's `name` field, which
// RunningAI's own writer (running-ai/scripts/create-today-workout.ps1) always
// sets to a Korean display label from running-ai/config/running-workout-types.json,
// optionally followed by free text such as "8.19km" or "6x800m".
//
// This list mirrors that registry's display_label values (and a few plain
// English synonyms, for a workout entered or renamed by hand) so a relabel in
// the registry does not silently stop being understood here - update both.
const TYPE_KEYWORDS = [
  { match: ['이지런', 'easy'], label: 'EASY' },
  { match: ['회복주', 'recovery'], label: 'RECOV' },
  { match: ['레이스 페이스', '레이스페이스', 'race pace', 'race'], label: 'RACE' },
  { match: ['스테디', 'steady'], label: 'STEADY' },
  { match: ['장거리', 'long run', 'long'], label: 'LONG' },
  { match: ['템포런', '템포', 'tempo'], label: 'TEMPO' },
  { match: ['역치주', '역치', 'threshold'], label: 'THRESH' },
  { match: ['인터벌', 'interval'], label: 'INTRVL' },
  { match: ['점진주', '점진', 'progression'], label: 'PROG' },
  { match: ['파틀렉', 'fartlek'], label: 'FARTLEK' },
  { match: ['언덕', 'hill'], label: 'HILL' },
  { match: ['스트라이드', 'stride'], label: 'STRIDE' },
  { match: ['휴식', 'rest', 'day off'], label: 'REST' },
];

function classifyTypeFromName(name) {
  const lower = String(name || '').toLowerCase();
  for (const entry of TYPE_KEYWORDS) {
    for (const key of entry.match) {
      if (lower.indexOf(key.toLowerCase()) !== -1) { return entry.label; }
    }
  }
  return null;
}

// "6x800m" / "6 X 800M" -> "6X800M". Returns null when the name carries no
// interval-repeat notation.
function extractIntervalMetric(name) {
  const m = String(name || '').match(/(\d{1,2})\s*[x×X]\s*(\d{2,5})\s*m\b/);
  if (!m) { return null; }
  return `${m[1]}X${m[2]}M`;
}

// "8.19km" / "8 km" -> "8KM" (whole km, matching the face's existing WEEK-row
// rounding convention). Returns null when the name carries no distance.
function extractDistanceMetric(name) {
  const m = String(name || '').match(/(\d{1,3}(?:\.\d{1,2})?)\s*km\b/i);
  if (!m) { return null; }
  const km = Math.round(parseFloat(m[1]));
  if (!(km > 0)) { return null; }
  return `${km}KM`;
}

// seconds -> "40M" (minutes, rounded). Returns null for anything non-positive.
function minutesMetric(movingTimeSeconds) {
  const sec = Number(movingTimeSeconds);
  if (!(sec > 0)) { return null; }
  const min = Math.round(sec / 60);
  if (min <= 0) { return null; }
  return `${min}M`;
}

// Builds the final TODAY-row label from one Intervals.icu event. Only values
// actually present in the event are used - nothing here invents a distance,
// duration or type the event does not carry.
function labelForEvent(event) {
  const name = String((event && event.name) || '');
  const type = classifyTypeFromName(name) || 'RUN';
  const metric = extractIntervalMetric(name) || extractDistanceMetric(name) || minutesMetric(event && event.moving_time) || null;
  return sanitizeLabel(metric ? `${type} ${metric}` : type);
}

// Defense in depth: the watch's own PixelFont only has 0-9, A-Z, space and
// ": + - / . % !", and its TODAY slot only has room for 14 of those glyphs
// (see ArcadeState.TODAY_TEXT_MAX / ArcadeTests.todayLabelNeverOverflowsSlot).
// This server already only emits strings built from that same set, but the
// label still crosses a trust boundary (built from free text an Intervals.icu
// event supplied) before it reaches the device, so it is clamped here too
// rather than trusting the watch to be the only thing that ever enforces it.
const TODAY_TEXT_MAX = 14;
function sanitizeLabel(label) {
  var upper = String(label || '').toUpperCase();
  var kept = '';
  for (var i = 0; i < upper.length && kept.length < TODAY_TEXT_MAX; i += 1) {
    var ch = upper[i];
    if (/[0-9A-Z :+\-./%!]/.test(ch)) { kept += ch; }
  }
  return kept;
}

module.exports = { classifyTypeFromName, extractIntervalMetric, extractDistanceMetric, minutesMetric, labelForEvent, sanitizeLabel, TODAY_TEXT_MAX };
