import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'
const source = await readFile(new URL('../src/core/acReplacer.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText
const { replaceText } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)
test('仅匹配原文，不发生二次串改', () => assert.deepEqual(replaceText('A B A', [{ search: 'A', replace: 'B' }, { search: 'B', replace: 'C' }]), { text: 'B C B', count: 3 }))
test('同位置最长匹配、空替换及 Unicode', () => assert.deepEqual(replaceText('小明小😀明', [{ search: '小', replace: '大' }, { search: '小明', replace: '' }, { search: '😀', replace: '🌸' }]), { text: '大🌸明', count: 3 }))
test('无匹配及空文件保留原内容', () => { assert.deepEqual(replaceText('abc\r\n', [{ search: 'x', replace: 'y' }]), { text: 'abc\r\n', count: 0 }); assert.deepEqual(replaceText('', [{ search: 'x', replace: 'y' }]), { text: '', count: 0 }) })
test('拒绝空规则、重复查找与过大输出', () => { assert.throws(() => replaceText('a', [])); assert.throws(() => replaceText('a', [{ search: '', replace: 'x' }])); assert.throws(() => replaceText('a', [{ search: 'a', replace: 'x' }, { search: 'a', replace: 'y' }])); assert.throws(() => replaceText('a'.repeat(50000), [{ search: 'a', replace: 'x'.repeat(200) }]), /超过/) })
