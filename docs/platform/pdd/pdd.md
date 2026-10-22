# 拼多多（多多买菜/驿站）模块研究文档（微信 H5 路线）

> 对应总规划：[../../../README.md](../../../README.md) 第 4 节「核心契约」。
>
> 任务模块：`:platform:pdd` ｜ 实现类：`PddAdapter`
> **本平台 `loginMode`**：微信 OAuth2 静默授权（snsapi_base），登录与业务接口均走 H5。

---

## 0. 当前状态（2026-09-26）

| 能力 | 对应契约方法 | 状态 |
|---|---|---|
| 登录态获取 | `login()` | ✅ **已完全打通并实测**（微信 OAuth code → `/weixin/login` 302 + Set-Cookie，token 有效期约 30 天） |
| 登录态校验 | `checkAlive()` | ✅ 任意业务接口调用，`40001`=session 超时；302 到 `undefined.html` = token 失效信号 |
| 身份码 | `fetchIdentityCode()` | ✅ **已完全打通并实测**（新 token + 自生成 anti-content 实测返回 success） |
| 快递列表 | `fetchExpressList()` | ✅ 同上，`station/package` 实测成功 |
| 取件码 | `fetchPickupCode()` | 同身份码链路 |

**研究素材目录**：`D:\身份码聚合app\pdd\`（js/ 下含全部逆向 chunk 与可运行脚本）。

---

## 1. 背景知识

1. **入口**：微信内打开 `https://mdkd.pinduoduo.com/weixin/package`（多多驿站 H5，Next.js SSR）。
2. **登录**：`wx913449bbda3b9f9a` 静默授权（snsapi_base，**state 固定为 `false`，无 session 校验**）→ redirect `/weixin/login?redirect_url=...&code=...&state=false` → 302 + `Set-Cookie: accesstoken`（**有效期约 30 天**，同时下发 `pdd_uin`/`pdd_user_id`）。
3. **真实 API 网关**：`https://mobile.yangkeduo.com/proxy/api/`（`mdkd.pinduoduo.com/api/...` 直连会被 nginx WAF 405）。
4. **鉴权仅需两个 Header**：`anti-content` + `accesstoken`，无需 Cookie。

## 2. 关键接口（均已实测）

```
POST https://mobile.yangkeduo.com/proxy/api/api/express_trackbox/generate_identification_code
POST https://mobile.yangkeduo.com/proxy/api/api/express_trackbox/station/package
```

- **必需 Headers**：`anti-content`（~330 字符，每次生成）、`accesstoken`
- **非必需**：Origin/Referer/UA/etag 实测去掉仍成功
- **Body**：可传 `{}`；`entry_source:"WX"` → `wx` 前缀码（expire ~587s），空 body → `dd` 前缀码
- 响应：`identification_code`（每调用生成新码）+ `expire_mills` + 绑定手机号信息
- 错误码：`40001`=session 超时；`70005`=ANTI_CONTENT_FAILED

## 3. anti-content 生成算法（已攻克）

### 3.1 生成链路（前端原始逻辑）

```
请求封装(模块 8G6N, chunk 6279bd53...js)
  → 模块 5+kD: async f()
      1. GET https://api.pinduoduo.com/api/server/_stm → { server_time }
      2. const inst = new C({ serverTime })     // C = 模块 eDaA → 转发 fbeZ
      3. return await inst.messagePackSync()    // 返回值即 anti-content
```

### 3.2 核心模块 fbeZ

- 位于 chunk `6279bd5325473390a0e0fdcf3a9edb92e97a02eb.3008b16fd1b0c3de4c77.js`，77.8KB 自包含 webpack bundle
- 内部：pako（deflate/crc32/adler）+ nanoid（字符集 `_~varfunctio0125634789bdegjhklmpqswxyzABCDEF...`）
- 字符串被 obfuscator.io RC4 字符串表混淆（搜不到 `messagePackSync` 字面量）
- 可见明文逻辑：`String(Date.now()).slice(0,10)` + 各字符 charCode 求和 %1000 补零3位 + base64 去 `=` + `_` + nanoid 随机串

### 3.3 复现方案（Node + jsdom，已实测通过）

- 可运行脚本：`pdd/js/test_live.js`（端到端：取 server_time → 生成 anti-content → 调身份码接口）
- 仅生成验证：`pdd/js/dbg_jsdom.js`
- 提取的模块文件：`pdd/js/module_fbeZ.js`（核心）、`pdd/js/module_8oxB.js`（process shim，签名 (e,t) 且 t=module）
- jsdom 关键配置：url 必须 `https://mdkd.pinduoduo.com/weixin/package`；UA 用微信内置浏览器 UA；global 上挂 window/document/navigator/localStorage/screen/self
- **重要**：生成值 330 字符 vs 真机 ~700 字符仍通过 → 服务端不校验完整指纹，jsdom 假指纹足够
- 补充：anti-content 旧值可长期重放（1.5h+ 验证），但自生成已无必要

## 4. 登录链路（已攻克，2026-09-26 实测）

### 4.1 完整流程

```
1. GET https://mdkd.pinduoduo.com/weixin/package   （不能带 accesstoken cookie）
   → 302 到微信 OAuth：
     https://open.weixin.qq.com/connect/oauth2/authorize
       ?appid=wx913449bbda3b9f9a
       &redirect_uri=https%3A%2F%2Fmdkd.pinduoduo.com%2Fweixin%2Flogin%3Fredirect_url%3D%2Fweixin%2Fpackage
       &response_type=code&scope=snsapi_base&state=false
2. 微信客户端生成 code（唯一需要微信环境的步骤，code 一次性、约 5 分钟有效）
3. GET https://mdkd.pinduoduo.com/weixin/login?redirect_url=%2Fweixin%2Fpackage&code=<CODE>&state=false
   Headers: PC微信UA（Cookie 可不带 _nano_fp，实测伪造值也成功） + Referer: https://open.weixin.qq.com/connect/oauth2/authorize
   → 302 Location: /weixin/package
   → Set-Cookie: accesstoken=...（30 天）+ pdd_uin + pdd_user_id
```

### 4.2 关键陷阱（排查中踩过的坑）

1. **过期 accesstoken 的坑**：带过期 token 请求 `/weixin/package`，服务端 302 到 `undefined.html`（下载引导兜底页，模板拼接 bug），**不跳 OAuth**——微信里表现为"失败 调用服务失败"。看到 `undefined.html` 即 token 失效信号，清 cookie 后恢复。
2. **code 必须是处女码**：浏览器重定向会自动消费 code；拦截时必须用 Fiddler `bpu`（请求发出前断点）。`bpafter` 在响应后断，code 已被服务器消费。二手 code 返回 `110000 调用服务失败`（无 Set-Cookie）。
3. **110000 的两种成因**：code 已被消费 / token 过期走到坏分支。`110000` = 上游服务异常，无 Set-Cookie；成功标志是 `302 + Set-Cookie: accesstoken`。
4. **请求复刻要求**：URL 参数必须带 `redirect_url=%2Fweixin%2Fpackage`，UA 用微信 UA（PC/手机均可）。
5. **登录不依赖 anti-content**：`/weixin/login` 是 SSR，交换由 PDD 服务端完成（AppSecret 在服务端），前端无任何接口调用。

### 4.3 运维结论

- 唯一需要微信客户端的步骤是获取 code；token 30 天有效 → 每月人工取一次 code 即可，其余全自动。
- 可运行验证脚本：`pdd/js/test_login2.js`（完整复刻浏览器登录请求）。

## 5. 待办

1. 封装 `PddAdapter` / client：`login(code) → getServerTime → genAntiContent → generateIdentificationCode / stationPackage`
2. accesstoken 长期时效验证（30 天说法来自 Set-Cookie expires 字段，待实测确认中途不因风控失效）
