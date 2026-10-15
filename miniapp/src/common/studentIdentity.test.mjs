import test from 'node:test'
import assert from 'node:assert/strict'
import { validStudentNumber, cleanRealName } from './studentIdentity.js'

test('student number keeps leading zero and requires exactly 14 ASCII digits', () => {
  assert.equal(validStudentNumber('00000000000001'), true)
  assert.equal(validStudentNumber('22222222222'), false)
  assert.equal(validStudentNumber('1234567890123'), false)
  assert.equal(validStudentNumber('123456789012345'), false)
  assert.equal(validStudentNumber('1234567890123a'), false)
  assert.equal(validStudentNumber(12345678901234), false)
})

test('real name trims whitespace but cannot be blank or oversized', () => {
  assert.equal(cleanRealName(' 测试学生 '), '测试学生')
  assert.equal(cleanRealName('   '), '')
  assert.equal(cleanRealName('x'.repeat(81)), '')
})
