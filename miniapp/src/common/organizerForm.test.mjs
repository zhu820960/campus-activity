import assert from 'node:assert/strict'
import test from 'node:test'
import { chinaDateTime, createOrganizerForm, organizerPayload, organizerQuickSchedule, organizerQuickSettings } from './organizerForm.js'

test('China local time round-trips to UTC', () => {
  const date = chinaDateTime('2026-10-15T02:30:00Z')
  assert.deepEqual(date, { date: '2026-10-15', time: '10:30' })
})

test('organizer form preserves selected coordinates and times', () => {
  const activity = { title: '讲座', description: '内容', location: '校内',
    longitude: 108.9, latitude: 34.2, capacity: 30,
    registrationOpensAt: '2026-10-14T01:00:00Z', registrationClosesAt: '2026-10-15T01:00:00Z',
    startsAt: '2026-10-15T02:00:00Z', endsAt: '2026-10-15T04:00:00Z',
    checkinOpensAt: '2026-10-15T01:30:00Z', checkinClosesAt: '2026-10-15T03:30:00Z' }
  const payload = organizerPayload(createOrganizerForm(activity))
  assert.equal(Date.parse(payload.startsAt), Date.parse(activity.startsAt))
  assert.equal(payload.longitude, activity.longitude)
  assert.equal(payload.latitude, activity.latitude)
})

test('missing schedule is rejected before sending', () => {
  const form = createOrganizerForm({ title: '讲座', description: '内容', location: '校内' })
  assert.throws(() => organizerPayload(form), /时间/)
})

test('quick schedule opens registration on publication and derives check-in around start', () => {
  const form = createOrganizerForm({ title: '讲座', description: '内容', location: '校内' })
  form.startsAt = { date: '2026-10-15', time: '10:00' }
  form.endsAt = { date: '2026-10-15', time: '12:00' }
  const now = new Date('2026-10-14T01:00:00Z')
  const payload = organizerPayload(organizerQuickSchedule(form, 30, 45, now))
  assert.equal(payload.registrationOpensAt, now.toISOString())
  assert.equal(payload.registrationClosesAt, '2026-10-15T01:30:00.000Z')
  assert.equal(payload.checkinOpensAt, '2026-10-15T01:30:00.000Z')
  assert.equal(payload.checkinClosesAt, '2026-10-15T02:45:00.000Z')
  assert.deepEqual(organizerQuickSettings(payload), { before: 30, after: 45, registrationBefore: 30 })
})

test('existing minute offsets are recovered without changing saved times', () => {
  assert.deepEqual(organizerQuickSettings({ startsAt: '2026-10-15T02:00:00Z',
    registrationClosesAt: '2026-10-15T01:00:00Z', checkinOpensAt: '2026-10-15T01:30:00Z',
    checkinClosesAt: '2026-10-15T02:30:00Z' }), { before: 30, after: 30, registrationBefore: 60 })
})

test('quick schedule rejects a closed registration window and check-in after event end', () => {
  const form = createOrganizerForm()
  form.startsAt = { date: '2026-10-15', time: '10:00' }
  form.endsAt = { date: '2026-10-15', time: '10:20' }
  assert.throws(() => organizerQuickSchedule(form, 30, 10, new Date('2026-10-15T01:31:00Z')), /报名截止/)
  assert.throws(() => organizerQuickSchedule(form, 30, 30, new Date('2026-10-15T01:00:00Z')), /活动结束/)
})

test('registration cutoff is configurable and all minute inputs reject blanks and fractions', () => {
  const form = createOrganizerForm()
  form.startsAt = { date: '2026-10-15', time: '10:00' }
  form.endsAt = { date: '2026-10-15', time: '12:00' }
  const now = new Date('2026-10-14T01:00:00Z')
  assert.equal(organizerPayload({ ...organizerQuickSchedule(form, 15, 45, now, 60), title: '讲座', description: '内容', location: '校内' }).registrationClosesAt, '2026-10-15T01:00:00.000Z')
  for (const invalid of ['', -1, 0.5, 10081]) {
    assert.throws(() => organizerQuickSchedule(form, 30, 30, now, invalid), /报名/)
  }
  assert.throws(() => organizerQuickSchedule(form, '', 30, now), /签到/)
})
