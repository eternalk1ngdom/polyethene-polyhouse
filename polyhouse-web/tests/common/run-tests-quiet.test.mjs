/**
 * 静默测试脚本汇总解析器单测：
 * 验证正常 spec 尾部统计、正文噪声干扰与缺失字段边界，防止误抓取测试自身打印日志。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { parseTestSummary } from '../../scripts/run-tests-quiet.mjs'

test('parseTestSummary 表驱动校验统计行提取与抗干扰能力', async (t) => {
  const cases = [
    {
      name: '标准 node:test spec 尾部输出精确提取',
      input: [
        '✔ 用例一 (1.2ms)',
        'ℹ tests 474',
        'ℹ suites 0',
        'ℹ pass 474',
        'ℹ fail 0',
        'ℹ skipped 2',
        'ℹ duration_ms 1420.5',
      ].join('\n'),
      expected: {
        tests: '474',
        pass: '474',
        fail: '0',
        skipped: '2',
        durationMs: '1420.5',
      },
    },
    {
      name: '测试正文打印普通 pass/fail 日志与伪造前缀行时，优先取末尾真实汇总',
      input: [
        'pass 9999',
        'tests 8888',
        'ℹ pass 7777',
        'ℹ fail 6666',
        '✔ 真实测试完成 (2.1ms)',
        'ℹ tests 12',
        'ℹ pass 11',
        'ℹ fail 0',
        'ℹ skipped 1',
        'ℹ duration_ms 320.25',
      ].join('\r\n'),
      expected: {
        tests: '12',
        pass: '11',
        fail: '0',
        skipped: '1',
        durationMs: '320.25',
      },
    },
    {
      name: '兼容 TAP 井号汇总前缀',
      input: '# tests 5\n# pass 4\n# fail 1\n# skipped 0\n# duration_ms 88.1',
      expected: {
        tests: '5',
        pass: '4',
        fail: '1',
        skipped: '0',
        durationMs: '88.1',
      },
    },
    {
      name: '空输出或缺失统计行安全返回 null',
      input: 'plain log without summary',
      expected: {
        tests: null,
        pass: null,
        fail: null,
        skipped: null,
        durationMs: null,
      },
    },
  ]

  for (const item of cases) {
    await t.test(item.name, () => {
      assert.deepEqual(parseTestSummary(item.input), item.expected)
    })
  }
})
