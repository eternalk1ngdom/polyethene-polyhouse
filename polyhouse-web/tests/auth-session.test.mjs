import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test, { beforeEach } from 'node:test'
import { AxiosError } from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import ts from 'typescript'

// 复用现有 TypeScript 依赖在内存编译真实模块；仅替换导入地址，不需要新增测试框架或构建配置。
async function moduleUrl(path, replacements = {}) {
  const source = await readFile(new URL(path, import.meta.url), 'utf8')
  let output = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  for (const dependency of ['axios', 'vue', 'pinia']) {
    output = output.replaceAll(`from '${dependency}'`, `from '${import.meta.resolve(dependency)}'`)
  }
  for (const [dependency, url] of Object.entries(replacements)) {
    output = output.replaceAll(`from '${dependency}'`, `from '${url}'`)
  }
  return `data:text/javascript;base64,${Buffer.from(output).toString('base64')}`
}

const apiUrl = await moduleUrl('../src/api/index.ts')
const authUrl = await moduleUrl('../src/api/auth.ts', { './index': apiUrl })
const storeUrl = await moduleUrl('../src/stores/useUserStore.ts', {
  '../api/auth': authUrl,
  '../api/index': apiUrl,
})
const { useUserStore } = await import(storeUrl)
const { useAccountUiStore } = await import(
  await moduleUrl('../src/stores/useAccountUiStore.ts', {
    '../api': apiUrl,
    './useUserStore': storeUrl,
  })
)
const { userDisplayName, userRoleLabel, avatarInitial, formatAccountNumber } = await import(
  await moduleUrl('../src/utils/userPresentation.ts')
)
const { AuthCommandError, login, register, getCurrentUser } = await import(authUrl)
const { ApiError, apiClient, apiRequest, setUnauthorizedHandler } = await import(apiUrl)
const { usernameError, passwordError, nicknameError } = await import(
  await moduleUrl('../src/utils/authValidation.ts')
)
const storage = new Map()
const profile = {
  id: '42',
  username: 'fixture',
  nickname: '测试',
  avatar: null,
  role: 'USER',
  experience: '9223372036854775807',
  level: 1,
  coins: '9223372036854775807',
}

function success(config, data = null) {
  return {
    config,
    data: { code: 0, message: '操作成功', data },
    status: 200,
    statusText: 'OK',
    headers: {},
  }
}

function statusFailure(config, status) {
  return new AxiosError('private-request-details', 'ERR_BAD_REQUEST', config, null, {
    ...success(config),
    status,
    data: { code: status * 100, message: 'private-response-details', data: null },
  })
}

function loginResult(accessToken = 'new-credential', user = profile) {
  return { accessToken, tokenType: 'Bearer', expiresAt: '2027-01-01T00:00:00Z', user }
}

beforeEach(() => {
  setActivePinia(createPinia())
  storage.clear()
  globalThis.localStorage = {
    getItem: (key) => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value),
    removeItem: (key) => storage.delete(key),
  }
  apiClient.defaults.adapter = async (config) => success(config)
  setUnauthorizedHandler((token) => useUserStore().expireSession(token))
})

test('退出和账号注销分别调用对应接口，成功后清除内存与两个缓存键', async () => {
  for (const command of ['logout', 'deregister']) {
    const store = useUserStore()
    store.setSession('fixture-credential', profile)
    apiClient.defaults.adapter = async (config) => {
      assert.equal(config.baseURL, '/api')
      assert.equal(config.url, `/auth/${command}`)
      assert.equal(config.method, 'post')
      assert.deepEqual(JSON.parse(config.data), { token: 'fixture-credential' })
      return success(config)
    }
    await store[command]()
    assert.equal(store.token, '')
    assert.equal(store.user, null)
    assert.equal(storage.size, 0)
  }
})

test('网络失败保留账号和会话，不把账号注销显示为成功', async () => {
  const store = useUserStore()
  store.setSession('fixture-credential', profile)
  apiClient.defaults.adapter = async () => {
    throw new Error('private-request-details')
  }
  await assert.rejects(store.deregister(), (error) => {
    assert.ok(error instanceof AuthCommandError)
    assert.equal(error.sessionExpired, false)
    assert.ok(!error.message.includes('private-request-details'))
    return true
  })
  assert.equal(store.token, 'fixture-credential')
  assert.equal(storage.size, 2)
})

test('401 清除失效会话，同时保留失败结果避免冒充注销完成', async () => {
  const store = useUserStore()
  store.setSession('expired-credential', profile)
  apiClient.defaults.adapter = async (config) => {
    throw new AxiosError('fixture', 'ERR_BAD_REQUEST', config, null, {
      ...success(config),
      status: 401,
    })
  }
  await assert.rejects(
    store.deregister(),
    (error) => error instanceof AuthCommandError && error.sessionExpired,
  )
  assert.equal(store.token, '')
  assert.equal(storage.size, 0)
})

test('旧退出请求完成不能清除等待期间建立的新会话', async () => {
  const store = useUserStore()
  store.setSession('old-credential', profile)
  let resolveRequest
  apiClient.defaults.adapter = (config) =>
    new Promise((resolve) => {
      resolveRequest = () => resolve(success(config))
    })
  const pending = store.logout()
  store.setSession('new-credential', profile)
  resolveRequest()
  await pending
  assert.equal(store.token, 'new-credential')
  assert.equal(storage.get('polyhouse_token'), 'new-credential')
})

test('经验与硬币按字符串缓存，保留大整数精度且不改变普通用户身份', () => {
  const store = useUserStore()
  store.setSession('fixture-credential', profile)
  const cached = JSON.parse(storage.get('polyhouse_user'))
  assert.equal(cached.experience, '9223372036854775807')
  assert.equal(cached.coins, '9223372036854775807')
  assert.equal(cached.level, 1)
  assert.equal(cached.role, 'USER')
  assert.equal(store.user.experience, profile.experience)
})

test('缓存用户资料即使被伪造成屋主也不直接恢复登录，必须读取 /me', async () => {
  storage.set('polyhouse_token', 'cached-credential')
  storage.set('polyhouse_user', '{invalid-private-cache')
  const store = useUserStore()
  assert.equal(store.isLoggedIn, false)
  assert.equal(store.user, null)
  assert.equal(store.status, 'checking')
  apiClient.defaults.adapter = async (config) => {
    assert.equal(config.url, '/auth/me')
    assert.equal(config.headers.get('Authorization'), 'Bearer cached-credential')
    assert.equal(config.params, undefined)
    return success(config, { ...profile, privateEntityField: 'should-not-escape' })
  }
  await store.restoreSession()
  assert.equal(store.isLoggedIn, true)
  assert.equal(store.user.role, 'USER')
  assert.equal(store.user.privateEntityField, undefined)
})

test('注册不建立会话，登录明确使用 PC 并且两个公开请求不发送旧 Token', async () => {
  const store = useUserStore()
  store.setSession('old-credential', profile)
  apiClient.defaults.adapter = async (config) => {
    assert.equal(config.headers.get('Authorization'), undefined)
    const request = JSON.parse(config.data)
    assert.equal(request.password, ' Fixture password ')
    if (config.url === '/auth/register') {
      assert.equal(request.nickname, '小屋')
      return success(config, profile)
    }
    assert.equal(config.url, '/auth/login')
    assert.equal(request.clientType, 'PC')
    return success(config, loginResult())
  }
  await register({ username: 'fixture', password: ' Fixture password ', nickname: '小屋' })
  assert.equal(store.token, 'old-credential')
  assert.equal(await store.login({ username: 'fixture', password: ' Fixture password ' }), true)
  assert.equal(store.token, 'new-credential')
  assert.equal(
    [...storage.values()].some((value) => value.includes('Fixture password')),
    false,
  )
})

test('错误密码的 401 不清理另一有效会话，失败后允许立即重试', async () => {
  const store = useUserStore()
  store.setSession('valid-credential', profile)
  apiClient.defaults.adapter = async (config) => {
    throw statusFailure(config, 401)
  }
  for (let count = 0; count < 6; count += 1) {
    await assert.rejects(login({ username: 'fixture', password: 'wrong-password' }), (error) => {
      assert.ok(error instanceof ApiError)
      assert.match(error.message, /账号或密码错误/)
      assert.equal(error.config, undefined)
      assert.equal(error.response, undefined)
      assert.equal(error.cause, undefined)
      assert.ok(!JSON.stringify(error).includes('private-'))
      return true
    })
  }
  assert.equal(store.token, 'valid-credential')
  apiClient.defaults.adapter = async (config) => success(config, loginResult())
  await store.login({ username: 'fixture', password: 'correct-password' })
  assert.equal(store.isLoggedIn, true)
})

test('会话恢复遇到网络失败保留 Token，隐藏未经确认的资料并支持重试', async () => {
  storage.set('polyhouse_token', 'cached-credential')
  const store = useUserStore()
  apiClient.defaults.adapter = async () => {
    throw new Error('private-network-details')
  }
  await store.restoreSession()
  assert.equal(store.status, 'unavailable')
  assert.equal(store.isLoggedIn, false)
  assert.equal(storage.get('polyhouse_token'), 'cached-credential')
  assert.ok(!store.sessionMessage.includes('private-'))
  apiClient.defaults.adapter = async (config) => success(config, profile)
  await store.restoreSession()
  assert.equal(store.status, 'authenticated')
  assert.equal(store.sessionMessage, '')
})

test('403 不清理会话凭据，受保护请求的 401 才清理当前身份', async () => {
  const store = useUserStore()
  store.setSession('valid-credential', profile)
  apiClient.defaults.adapter = async (config) => {
    throw statusFailure(config, 403)
  }
  await assert.rejects(
    getCurrentUser(store.token),
    (error) => error.status === 403 && !error.sessionExpired,
  )
  assert.equal(store.isLoggedIn, true)
  await store.restoreSession()
  assert.equal(store.status, 'unavailable')
  assert.equal(store.token, 'valid-credential')
  apiClient.defaults.adapter = async (config) => {
    throw statusFailure(config, 401)
  }
  await store.restoreSession()
  assert.equal(store.token, '')
  assert.equal(store.status, 'anonymous')
  assert.equal(storage.size, 0)
  assert.match(store.sessionMessage, /失效/)
})

test('相同版本的并发恢复只发送一个请求', async () => {
  storage.set('polyhouse_token', 'cached-credential')
  const store = useUserStore()
  let finish
  let requests = 0
  apiClient.defaults.adapter = (config) => {
    requests += 1
    return new Promise((resolve) => {
      finish = () => resolve(success(config, profile))
    })
  }
  const first = store.restoreSession()
  const second = store.restoreSession()
  assert.equal(requests, 1)
  finish()
  await Promise.all([first, second])
  assert.equal(store.isLoggedIn, true)
})

for (const failure of [false, true]) {
  test(`旧恢复${failure ? '401' : '成功'}响应不能覆盖新登录`, async () => {
    storage.set('polyhouse_token', 'old-credential')
    const store = useUserStore()
    let finish
    apiClient.defaults.adapter = (config) =>
      new Promise((resolve, reject) => {
        finish = () =>
          failure ? reject(statusFailure(config, 401)) : resolve(success(config, profile))
      })
    const pending = store.restoreSession()
    store.setSession('new-credential', { ...profile, nickname: '新资料' })
    finish()
    await pending
    assert.equal(store.token, 'new-credential')
    assert.equal(store.user.nickname, '新资料')
    assert.equal(store.isLoggedIn, true)
  })
}

test('两个登录乱序返回时只保留最后一次意图', async () => {
  const store = useUserStore()
  const completions = []
  apiClient.defaults.adapter = (config) =>
    new Promise((resolve) => {
      completions.push((token) => resolve(success(config, loginResult(token))))
    })
  const first = store.login({ username: 'first', password: 'fixture-password' })
  const second = store.login({ username: 'second', password: 'fixture-password' })
  completions[1]('second-credential')
  assert.equal(await second, true)
  completions[0]('first-credential')
  assert.equal(await first, false)
  assert.equal(store.token, 'second-credential')
})

test('相同 Token 重新建立资料时，旧退出响应也不能清除新版本', async () => {
  const store = useUserStore()
  store.setSession('shared-credential', profile)
  let finish
  apiClient.defaults.adapter = (config) =>
    new Promise((resolve) => {
      finish = () => resolve(success(config))
    })
  const pending = store.logout()
  store.setSession('shared-credential', { ...profile, nickname: '新版本' })
  finish()
  await pending
  assert.equal(store.user.nickname, '新版本')
})

test('另一标签页更新 Token 或退出时同步身份，忽略伪造的缓存资料', async () => {
  const store = useUserStore()
  store.setSession('old-credential', profile)
  storage.set('polyhouse_token', 'other-tab-credential')
  storage.set('polyhouse_user', JSON.stringify({ role: 'OWNER' }))
  apiClient.defaults.adapter = async (config) =>
    success(config, { ...profile, nickname: '最新资料' })
  await store.syncStoredSession()
  assert.equal(store.token, 'other-tab-credential')
  assert.equal(store.user.role, 'USER')
  storage.delete('polyhouse_token')
  await store.syncStoredSession()
  assert.equal(store.isLoggedIn, false)
  assert.equal(store.user, null)
})

test('旧退出响应到达时即使存储事件尚未处理，也不删除另一标签页新 Token', async () => {
  const store = useUserStore()
  store.setSession('old-credential', profile)
  let finish
  apiClient.defaults.adapter = (config) =>
    new Promise((resolve) => {
      finish = () => resolve(success(config))
    })
  const pending = store.logout()
  storage.set('polyhouse_token', 'other-tab-credential')
  finish()
  await pending
  assert.equal(storage.get('polyhouse_token'), 'other-tab-credential')
})

test('恢复响应到达前共享缓存已变化时，重新确认新 Token，不能写回旧资料', async () => {
  storage.set('polyhouse_token', 'old-credential')
  const store = useUserStore()
  let finish
  apiClient.defaults.adapter = (config) => {
    if (config.headers.get('Authorization') === 'Bearer other-tab-credential')
      return Promise.resolve(success(config, { ...profile, nickname: '另一页' }))
    return new Promise((resolve) => {
      finish = () => resolve(success(config, profile))
    })
  }
  const pending = store.restoreSession()
  storage.set('polyhouse_token', 'other-tab-credential')
  finish()
  await pending
  assert.equal(store.token, 'other-tab-credential')
  assert.equal(store.user.nickname, '另一页')
})

test('拒绝丢失精度的资料与畸形响应，不清除仍待确认的 Token', async () => {
  storage.set('polyhouse_token', 'cached-credential')
  const store = useUserStore()
  for (const invalid of [
    { ...profile, experience: 9223372036854775807 },
    { ...profile, role: 'ADMIN' },
    { ...profile, coins: '-1' },
    null,
  ]) {
    apiClient.defaults.adapter = async (config) => success(config, invalid)
    await store.restoreSession()
    assert.equal(store.isLoggedIn, false)
    assert.equal(store.status, 'unavailable')
    assert.equal(store.token, 'cached-credential')
  }
  apiClient.defaults.adapter = async (config) => ({
    ...success(config),
    data: '<html>private-response-details</html>',
  })
  await assert.rejects(
    apiRequest({ url: '/auth/me' }, (value) => value, store.token),
    (error) => error.status === 502 && !error.message.includes('private-'),
  )
})

test('HTTP 200 的业务失败也不能建立身份或被视为成功', async () => {
  const store = useUserStore()
  apiClient.defaults.adapter = async (config) => ({
    ...success(config),
    data: { code: 40900, message: 'private-details', data: null },
  })
  await assert.rejects(
    register({ username: 'fixture', password: 'fixture-password', nickname: '测试' }),
    (error) => error.status === 409,
  )
  assert.equal(store.isLoggedIn, false)
})

test('浏览器禁用存储时仍能在内存登录，且提供明确提示', async () => {
  globalThis.localStorage = {
    getItem() {
      throw new Error('private-storage-details')
    },
    setItem() {
      throw new Error('private-storage-details')
    },
    removeItem() {
      throw new Error('private-storage-details')
    },
  }
  const store = useUserStore()
  apiClient.defaults.adapter = async (config) => success(config, loginResult())
  await store.login({ username: 'fixture', password: 'fixture-password' })
  assert.equal(store.isLoggedIn, true)
  assert.match(store.storageMessage, /刷新/)
  assert.ok(!store.storageMessage.includes('private-'))
  store.clearSession()
  assert.equal(store.isLoggedIn, false)
})

test('缓存写入一半失败时清除当前会话片段，不留下刷新幽灵登录', () => {
  globalThis.localStorage.setItem = (key, value) => {
    if (key === 'polyhouse_user') throw new Error('quota')
    storage.set(key, value)
  }
  const store = useUserStore()
  store.setSession('fixture-credential', profile)
  assert.equal(store.isLoggedIn, true)
  assert.equal(storage.size, 0)
  assert.match(store.storageMessage, /保存登录状态/)
})

test('前端校验对齐账号白名单、昵称长度及 BCrypt 多字节边界，不裁剪密码', () => {
  assert.equal(usernameError('user_1'), null)
  for (const value of ['abc', 'a'.repeat(33), ' user', '用户账号', 'user\n'])
    assert.ok(usernameError(value))
  assert.equal(passwordError(' password '), null)
  assert.equal(passwordError('密'.repeat(24)), null)
  assert.ok(passwordError('密'.repeat(25)))
  assert.equal(passwordError('😀'.repeat(18)), null)
  assert.ok(passwordError('😀'.repeat(19)))
  assert.ok(passwordError(' '.repeat(8)))
  assert.ok(passwordError('short'))
  assert.equal(nicknameError('屋'.repeat(32)), null)
  assert.ok(nicknameError('屋'.repeat(33)))
  assert.ok(nicknameError('   '))
})

test('个人资料展示保留大整数和 emoji，并将三种角色与等级分开展示', () => {
  assert.equal(userDisplayName({ ...profile, nickname: '   ' }), 'fixture')
  assert.equal(userDisplayName(profile), '测试')
  assert.equal(avatarInitial('😀小屋'), '😀')
  assert.equal(avatarInitial(''), '屋')
  assert.equal(formatAccountNumber(profile.coins), '9,223,372,036,854,775,807')
  assert.equal(formatAccountNumber('0'), '0')
  assert.equal(userRoleLabel('USER'), '普通居民')
  assert.equal(userRoleLabel('INSIDER'), '特邀熟人')
  assert.equal(userRoleLabel('OWNER'), '聚妙屋屋主')
})

test('未确认身份不能打开注销确认，也不会发出注销请求', async () => {
  storage.set('polyhouse_token', 'cached-credential')
  const store = useUserStore()
  const ui = useAccountUiStore()
  let calls = 0
  apiClient.defaults.adapter = async (config) => {
    calls += 1
    return success(config)
  }
  ui.openCancellation()
  assert.equal(ui.authVisible, true)
  assert.equal(ui.cancellationVisible, false)
  assert.equal(await ui.deregister('注销账号', true), false)
  assert.equal(await ui.logout(), false)
  assert.equal(calls, 0)
  assert.equal(store.token, 'cached-credential')
})

test('注销必须打开确认、同意影响且输入完全一致的确认词，取消后旧确认作废', async () => {
  const store = useUserStore()
  store.setSession('fixture-credential', profile)
  const ui = useAccountUiStore()
  let calls = 0
  apiClient.defaults.adapter = async (config) => {
    calls += 1
    return success(config)
  }
  assert.equal(await ui.deregister('注销账号', true), false)
  ui.openCancellation()
  assert.equal(ui.cancellationUsername, profile.username)
  for (const [text, agreed] of [
    ['', true],
    ['注销', true],
    ['注销账号 ', true],
    ['注销账号', false],
  ]) {
    assert.equal(await ui.deregister(text, agreed), false)
  }
  ui.closeCancellation()
  assert.equal(ui.cancellationUsername, '')
  assert.equal(await ui.deregister('注销账号', true), false)
  assert.equal(calls, 0)
  assert.equal(store.isLoggedIn, true)
})

test('确认期间换号会关闭弹窗，不能用旧确认注销新身份', async () => {
  const store = useUserStore()
  store.setSession('old-credential', profile)
  const ui = useAccountUiStore()
  ui.openCancellation()
  store.setSession('new-credential', { ...profile, id: '99', username: 'another' })
  assert.equal(ui.cancellationVisible, false)
  assert.equal(ui.cancellationUsername, '')
  assert.equal(await ui.deregister('注销账号', true), false)
  assert.equal(store.user.id, '99')
})

test('注销提交期间跨入口阻止重复命令和关闭，成功后再显示确认反馈', async () => {
  const store = useUserStore()
  store.setSession('fixture-credential', profile)
  const ui = useAccountUiStore()
  ui.openCancellation()
  let finish
  let calls = 0
  apiClient.defaults.adapter = (config) => {
    calls += 1
    assert.equal(config.url, '/auth/deregister')
    assert.deepEqual(JSON.parse(config.data), { token: 'fixture-credential' })
    return new Promise((resolve) => {
      finish = () => resolve(success(config))
    })
  }
  const pending = ui.deregister('注销账号', true)
  assert.equal(ui.pending, 'deregister')
  assert.equal(await ui.deregister('注销账号', true), false)
  assert.equal(await ui.logout(), false)
  ui.closeCancellation()
  assert.equal(ui.cancellationVisible, true)
  assert.equal(ui.notice, '')
  finish()
  assert.equal(await pending, true)
  assert.equal(calls, 1)
  assert.equal(store.isLoggedIn, false)
  assert.equal(ui.cancellationVisible, false)
  assert.match(ui.notice, /账号已注销/)
})

test('注销网络失败保留身份与确认，不把未确认结果显示成成功', async () => {
  const store = useUserStore()
  store.setSession('fixture-credential', profile)
  const ui = useAccountUiStore()
  ui.openCancellation()
  apiClient.defaults.adapter = async () => {
    throw new Error('private-failure')
  }
  assert.equal(await ui.deregister('注销账号', true), false)
  assert.equal(store.isLoggedIn, true)
  assert.equal(ui.cancellationVisible, true)
  assert.equal(ui.notice, '')
  assert.ok(ui.actionError)
  assert.ok(!ui.actionError.includes('private-failure'))
  apiClient.defaults.adapter = async (config) => success(config)
  assert.equal(await ui.deregister('注销账号', true), true)
})

test('注销返回401只显示身份失效，不把注销命令冒充成功', async () => {
  const store = useUserStore()
  store.setSession('expired-credential', profile)
  const ui = useAccountUiStore()
  ui.openCancellation()
  apiClient.defaults.adapter = async (config) => {
    throw statusFailure(config, 401)
  }
  assert.equal(await ui.deregister('注销账号', true), false)
  assert.equal(store.isLoggedIn, false)
  assert.equal(ui.notice, '')
  assert.equal(ui.cancellationVisible, false)
  assert.match(store.sessionMessage, /失效/)
})

for (const failure of [false, true]) {
  test(`注销${failure ? '失败' : '成功'}旧响应不影响等待期间建立的新身份`, async () => {
    const store = useUserStore()
    store.setSession('old-credential', profile)
    const ui = useAccountUiStore()
    ui.openCancellation()
    let finish
    apiClient.defaults.adapter = (config) =>
      new Promise((resolve, reject) => {
        finish = () => (failure ? reject(statusFailure(config, 401)) : resolve(success(config)))
      })
    const pending = ui.deregister('注销账号', true)
    store.setSession('new-credential', { ...profile, id: '99', username: 'another' })
    finish()
    assert.equal(await pending, false)
    assert.equal(store.user.id, '99')
    assert.equal(store.isLoggedIn, true)
    assert.equal(ui.notice, '')
    assert.equal(ui.actionError, '')
    assert.equal(ui.cancellationVisible, false)
  })
}

test('退出成功反馈可收起，新登录自动清除旧反馈', async () => {
  const store = useUserStore()
  store.setSession('fixture-credential', profile)
  const ui = useAccountUiStore()
  assert.equal(await ui.logout(), true)
  assert.match(ui.notice, /已退出登录/)
  ui.dismissNotice()
  assert.equal(ui.notice, '')
  store.setSession('another-credential', profile)
  await ui.logout()
  store.setSession('new-credential', profile)
  assert.equal(ui.notice, '')
})

test('退出网络失败保留资料并提供错误，不发成功反馈', async () => {
  const store = useUserStore()
  store.setSession('fixture-credential', profile)
  const ui = useAccountUiStore()
  apiClient.defaults.adapter = async () => {
    throw new Error('private-failure')
  }
  assert.equal(await ui.logout(), false)
  assert.equal(store.isLoggedIn, true)
  assert.equal(ui.notice, '')
  assert.ok(ui.actionError)
  assert.equal(ui.pending, null)
})
