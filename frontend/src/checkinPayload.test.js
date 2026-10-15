import test from 'node:test'
import assert from 'node:assert/strict'
import { buildCheckinPayload } from './checkinPayload.js'

test('QR payload carries the activity ID and opaque token without a Web URL', () => {
  assert.deepEqual(JSON.parse(buildCheckinPayload(12, 'a+b/c?=')), {
    type: 'campus-activity-checkin', version: 1, activityId: 12, token: 'a+b/c?=',
  })
})

test('invalid QR payload inputs are rejected', () => {
  for (const id of [0, -1, 1.5, NaN]) assert.throws(() => buildCheckinPayload(id, 'abc'))
  assert.throws(() => buildCheckinPayload(1, ''))
})
