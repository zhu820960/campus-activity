import test from 'node:test'
import assert from 'node:assert/strict'
import { buildCheckinCode, parseCheckinCode } from './scan.js'

const valid = { type: 'campus-activity-checkin', version: 1, activityId: 7,
  token: `v2.1234567890123.${'a'.repeat(43)}.${'b'.repeat(43)}` }

test('accepts a structured signed-code envelope', () => {
  assert.deepEqual(parseCheckinCode(JSON.stringify(valid)), { activityId: 7, token: valid.token })
  assert.deepEqual(parseCheckinCode(buildCheckinCode(valid.activityId, valid.token)),
    { activityId: 7, token: valid.token })
})

for (const [name, value] of [
  ['plain URL', 'https://example.com/checkin'],
  ['wrong type', JSON.stringify({ ...valid, type: 'other' })],
  ['unsupported version', JSON.stringify({ ...valid, version: 2 })],
  ['invalid activity ID', JSON.stringify({ ...valid, activityId: '7' })],
  ['missing token', JSON.stringify({ ...valid, token: '' })],
  ['malformed token', JSON.stringify({ ...valid, token: 'v2.bad' })],
  ['oversized input', 'x'.repeat(1025)]
]) {
  test(`rejects ${name}`, () => assert.throws(() => parseCheckinCode(value)))
}
