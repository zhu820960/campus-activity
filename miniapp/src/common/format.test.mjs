import test from 'node:test'
import assert from 'node:assert/strict'
import { activityBadge, registrationLabel, locationReady } from './format.js'

const now = Date.parse('2026-10-10T10:00:00Z')
const activity = { status: 'PUBLISHED', registrationOpensAt: '2026-10-10T09:00:00Z',
  registrationClosesAt: '2026-10-10T11:00:00Z', startsAt: '2026-10-10T12:00:00Z',
  registeredCount: 1, capacity: 2 }

test('registration availability follows server time fields', () => {
  assert.equal(registrationLabel(activity, now), '可报名')
  assert.equal(registrationLabel({ ...activity, capacity: 1 }, now), '名额已满')
  assert.equal(registrationLabel({ ...activity, status: 'PENDING' }, now), '暂不可报名')
  assert.equal(registrationLabel({ ...activity, status: 'CANCELLED' }, now), '活动已取消')
  assert.equal(registrationLabel({ ...activity, registrationClosesAt: '2026-10-10T09:59:00Z' }, now), '报名已截止')
})

test('map needs both valid saved coordinates', () => {
  assert.equal(locationReady({ latitude: 34.34, longitude: 108.93 }), true)
  assert.equal(locationReady({ latitude: null, longitude: 108.93 }), false)
  assert.equal(locationReady({ latitude: 100, longitude: 108.93 }), false)
})

test('activity badge separates event phase from registration availability', () => {
  assert.deepEqual(activityBadge(activity, now), { text: '可报名', tone: 'open' })
  assert.deepEqual(activityBadge({ ...activity, startsAt: '2026-10-10T09:30:00Z' }, now),
    { text: '进行中', tone: 'live' })
  assert.deepEqual(activityBadge({ ...activity, endsAt: '2026-10-10T09:59:00Z' }, now),
    { text: '已结束', tone: 'ended' })
  assert.deepEqual(activityBadge({ ...activity, status: 'CANCELLED', endsAt: '2026-10-10T09:59:00Z' }, now),
    { text: '已取消', tone: 'closed' })
})
