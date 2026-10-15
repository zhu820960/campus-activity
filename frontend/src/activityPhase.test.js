import test from 'node:test'
import assert from 'node:assert/strict'
import { activityPhase } from './activityPhase.js'

const activity = { startsAt: '2026-10-09T10:00:00Z', endsAt: '2026-10-09T12:00:00Z', status: 'PUBLISHED' }
const at = (time) => Date.parse(time)

test('activity phases use exclusive end boundary and are independent of review status', () => {
  assert.equal(activityPhase(activity, at('2026-10-09T09:59:59Z')), 'UPCOMING')
  assert.equal(activityPhase(activity, at(activity.startsAt)), 'ONGOING')
  assert.equal(activityPhase(activity, at('2026-10-09T11:00:00Z')), 'ONGOING')
  assert.equal(activityPhase(activity, at(activity.endsAt)), 'ENDED')
  assert.equal(activityPhase({ ...activity, status: 'PENDING' }, at(activity.startsAt)), 'ONGOING')
})
