# 淘宝（菜鸟驿站）模块开发文档

> 对应总规划：[../../README.md](../../README.md) 第 4 节「核心契约」、第 7 节「平台模块开发规范」。
> 阅读本文档前请先读总规划的 4.1（PlatformAdapter 接口）、4.3（数据模型）、4.4（错误模型）。
>
> 任务模块：`:platform:taobao` ｜ 实现类：`TaobaoAdapter` ｜ 分支：`platform/taobao`

***

## 0. 任务目标

实现淘宝平台（菜鸟驿站末端体系）的完整能力：

| 能力    | 对应契约方法                                | 状态（2026-09-29 实测更新）                                      |
| ----- | ------------------------------------- | -------------------------------------------------------- |
| 登录态获取 | `loginUrl()` + `detectLoginSuccess()` | ✅ **纯协议登录已跑通**（PC 短信验证码，见 3.4），WebView 方案保留              |
| 登录态校验 | `checkAlive()`                        | ✅ 接口已验证（`search.getTimeStamp` / `user.get`）              |
| 身份码   | `fetchIdentityCode()`                 | ✅ **接口已实测打通**（`getIdentityMessage`，见 5.2），可跳过 WebView 方案 |
| 快递列表  | `fetchExpressList()`                  | ✅ **实测打通**（`queryMultiStationPackages` 含完整取件码，见第 6 节，2026-09-29） |
| 按运单号查 | —（辅助查询）                              | ✅ **实测打通**（`search.multiOneSearch`，见 6.3，2026-09-29）     |
| 取件码   | `fetchPickupCode()`                   | ✅ **实测打通**（列表接口直接返回完整取件码，见第 6 节）                         |

**开发策略（更新）**：纯协议方案已验证可行——PC 端短信登录无滑块、登录接口无反爬参数（`ua`/`umidToken` 传空即可）、mtop 签名为标准 MD5。可直接按方案 B 开发，WebView 仅作页面展示降级路径。复现脚本见 `taobao/js/protocol_login.js` + `fetch_idcode.js`。

***

## 1. 背景知识（先读）

淘宝 App 内的「菜鸟驿站」身份码/包裹页面不是原生页面，而是**淘宝 TinyApp（uniapp H5）**，运行在 `pages-fast.m.taobao.com` 域下，模块前缀 `last-mile-fe`（末端物流前端）。我们已确认两个 TinyApp：

| appId   | 模块                                  | 说明                             |
| ------- | ----------------------------------- | ------------------------------ |
| 1100410 | `last-mile-fe/m-end-identity-code`  | 身份码专用应用，仅 `home`、`pickup` 两个页面 |
| 1011717 | `last-mile-fe/end-collect-platform` | 驿站主应用（取件平台），含包裹列表/详情/搜索等全部页面   |

两者 `bizInfo.appkey` 相同（`34401729`），业务描述均为「手机淘宝扫码查取件更便捷」。

**关键结论：**

1. 这些页面是 H5，**可以放进我们的 LoginWebView 直接打开**（登录后）；
2. 页面数据通过淘宝 **MTOP 网关**（`h5api.m.taobao.com`）请求，走 H5 签名机制（见第 7 节）；
3. 所以每类能力都有两条实现路径：
   - **方案 A（保底）**：WebView 直接加载对应页面，用户直接看；
   - **方案 B（目标）**：抓包拿到页面背后的 mtop 接口 → 用保存的 Cookie + 签名直调 → 数据落到统一模型，由我们自己的 Compose UI 渲染。

***

## 2. 已知线索：页面地图（已验证可访问）

以下 URL 均已实测返回有效内容（manifest 清单）。页面本身需要淘宝登录 Cookie 才会出数据。

### 2.1 身份码应用（1100410 / m-end-identity-code）

| 页面     | URL                                                                                                                                    | 用途                          |
| ------ | -------------------------------------------------------------------------------------------------------------------------------------- | --------------------------- |
| home   | `https://pages-fast.m.taobao.com/wow/z/uniapp/1100410/last-mile-fe/m-end-identity-code/home?x-ssr=true&skeleton=true&forceThemis=true` | **身份码主页**（已确认登录后可直接打开显示身份码） |
| pickup | `https://pages-fast.m.taobao.com/wow/z/uniapp/1100410/last-mile-fe/m-end-identity-code/pickup?x-ssr=true`                              | 出示身份码取件页                    |

### 2.2 驿站主应用（1011717 / end-collect-platform）

URL 前缀统一为 `https://pages-fast.m.taobao.com/wow/z/uniapp/1011717/last-mile-fe/end-collect-platform/`，加 `?x-ssr=true`：

| 页面 id                                   | 与我们需求的对应关系                        |
| --------------------------------------- | --------------------------------- |
| `home` / `home-ssr` / `home-ssr-index`  | **驿站首页（包裹列表入口）→ 快递列表数据源（重点抓包对象）** |
| `identity-code`                         | 身份码（第二线索源）                        |
| `package-detail`                        | **包裹详情 → 取件码/单件数据源（重点抓包对象）**      |
| `package-search` / `package-search-new` | 包裹搜索                              |
| `parcel-collected`                      | 已取件包裹列表                           |
| `quick-pick-up`                         | 快速取件                              |
| `my` / `personal-center`                | 个人中心（站点信息）                        |
| `no-site-info`                          | 无站点信息兜底页                          |

> 完整 manifest（含页面版本号）可通过 `GET https://pages-fast.m.taobao.com/wow/z/uniapp/1011717/` 直接获取，无需登录。

***

## 3. 登录流程

### 3.1 loginUrl 候选

按优先级在真机 WebView 里实测，选第一个可正常完成登录的：

1. `https://login.m.taobao.com/login.htm` （淘宝 H5 登录页，支持账密/短信）
2. `https://h5.m.taobao.com/mlapp/login.html` （H5 登录引导页）
3. `https://login.taobao.com/member/login.jhtml` （PC 页，UA 伪装手机时不推荐，兜底用）

> 注：以上为候选，**以实测为准**，确定后在本文档更新并把实际可用 URL 固化进 `TaobaoAdapter.loginUrl()`。登录方式（账密/短信/扫码）由用户自选，我们不干预。

### 3.2 WebView 配置要求（core:webview 通用，平台模块只声明）

- 手机 UA（对齐淘宝 H5，避免被降级到 PC 页）；
- `javaScriptEnabled = true`、`domStorageEnabled = true`（登录页依赖 localStorage）；
- 接受第三方 Cookie（登录跳转会跨 `login.m.taobao.com` → `h5.m.taobao.com` 等域）；
- 每个请求回调 `detectLoginSuccess(req, cookies)`。

### 3.3 登录成功判定（detectLoginSuccess 实现）

淘宝登录成功后，`.taobao.com` 域会写入以下关键 Cookie（**判定依据**）：

| Cookie                      | 含义                         | 必需性                 |
| --------------------------- | -------------------------- | ------------------- |
| `cookie2`                   | 登录态主 Cookie                | **必需**              |
| `unb`                       | 用户数字 ID                    | **必需**（写入 extra）    |
| `_tb_token_`                | 页面 token                   | 建议保存                |
| `_m_h5_tk` / `_m_h5_tk_enc` | **MTOP 签名 token（第 7 节要用）** | **必需**（调 mtop 接口必须） |
| `tracknick` / `lgc` / `dnk` | 用户昵称（URL 编码）               | 可选（写入 extra，展示用）    |
| `sg` / `skt` / `csg`        | 辅助登录态                      | 建议保存                |

**判定规则（幂等）：**

```
若 cookies 同时包含 cookie2 与 unb 且非空 → 登录成功
Credential.token = 该时刻 .taobao.com 域下全部 Cookie 拼接的 Cookie 串
Credential.extra = { "unb": ..., "tracknick": ..., "_m_h5_tk": ... }
重复命中直接返回相同结果，不重复落盘
```

> 注意：`_m_h5_tk` 可能要等页面发出第一个 mtop 请求后才写入。若命中 cookie2 时 `_m_h5_tk` 尚未出现，**继续监听**，直到两者齐备再返回 Credential（或登录后引导 WebView 顺访一次 `https://h5.m.taobao.com` 任意页触发 mtop）。

### 3.4 纯协议登录（2026-09-27 实测打通，无需浏览器/WebView）

PC 淘宝登录页（havanaone）短信登录链路已完整逆向，**接口无 baxia 反爬参数要求**（`ua`、`umidToken` 传空串即可），全程 Node 直连成功：

```
1. GET  https://login.taobao.com/havanaone/login/login.htm?bizName=taobao
        → 种下 XSRF-TOKEN/cookie2 等 cookie；从 HTML 解析 _csrf（32位hex，"csrfId" 字段）
2. POST https://login.taobao.com/havanaone/loginLegacy/sms/sendSms.do?bizEntrance=taobao_pc&bizName=taobao
        body: phoneCode=86&loginId={手机号}&loginId2=&countryCode=CN&codeLength=4
              &isIframe=false&banThirdPartyCookie=false&documentReferer=&defaultView=sms
              &ua=&umidGetStatusVal=&screenPixel=1707x960&navlanguage=zh-CN&navUserAgent={UA}
              &navPlatform=Win32&hitRSA2048Gray=true&bizEntrance=taobao_pc&bizName=taobao
              &_csrf={上面解析值}&lang=zh_CN&umidToken=&umidTag=NOT_INIT&weiBoMpBridge=
              &jsVersion=0.10.37&deviceId={cna cookie}&pageTraceId={自造}
        → {"smsToken":"sm2_xxx"} （验证码同时发到手机）
3. POST https://login.taobao.com/havanaone/loginLegacy/sms/login.do?bizEntrance=taobao_pc&bizName=taobao
        body: 同上公共字段 + loginId={手机号}&loginId2=&phoneCode=86&countryCode=CN
              &smsCode={4位验证码}&smsToken={step2返回}&keepLogin=false&contextToken=
        → {"loginResult":"success"}，Set-Cookie 写入全套登录态（cookie2/unb/skt/sg/_l_g_/tracknick...）
```

实测注意：

- 请求头仅需 `User-Agent` + `Content-Type: application/x-www-form-urlencoded` + `Origin/Referer: https://login.taobao.com`；全程无滑块/验证码图片；
- `smsToken` 有时效（分钟级），send 与 login 需在同一会话（cookie jar 连续）；
- `_csrf` 是会话级，登录页 HTML 解析，勿写死；
- 短信验证码 4 位数字，5 分钟内有效；
- **登录成功 cookie 含 httpOnly（cookie2/skt 等），WebView** **`document.cookie`** **读不到**——纯协议方案反而不受此限制，这是它优于 WebView 抓 cookie 的地方；
- 风控敏感：一天多次登录可能触发校验，登录态要持久化复用（accesstoken 类比 PDD）。

参考实现：`taobao/js/protocol_login.js`（send/login 两步，cookie jar 落盘 `cookies.json`）。

***

## 4. 登录态校验（checkAlive）

不单独找校验接口，**复用业务接口探测**（省一次逆向）：

1. 用 Credential 调用一次「快递列表第一页」mtop 接口（见第 6 节，接口确定前可临时调 `mtop.common.getTimestamp` 级别的公共接口验证 token 有效性）；
2. 按 mtop 返回的 `ret` 数组判断：
   - `SUCCESS` → true
   - `FAIL_SYS_SESSION_EXPIRED` / `FAIL_SYS_TOKEN_EXOIRED` / `FAIL_SYS_TOKEN_EMPTY` → **先走第 7 节 token 刷新重试一次**，仍失败 → false（映射 `NeedRelogin`）；
3. `checkAlive` 结果写入 `PlatformLoginState.lastCheckAt`，避免每次刷新都探测（缓存 5 分钟）。

***

## 5. 身份码获取（fetchIdentityCode）

已知登录后可直接打开身份码页面：`1100410/m-end-identity-code/home`（2.1 节 URL）。

### 方案 A：WebView 直载（保底，先做）

1. 把 Credential 的 Cookie 写回 `CookieManager`（按域写回 `.taobao.com`）；
2. WebView 加载 2.1 的 home URL；
3. 注入 JS 隐藏页面导航/悬浮元素（可选，美化）；
4. 页面 `onPageFinished` 且身份码元素渲染完成后截图/直接展示。

- 优点：不依赖逆向，稳；
- 缺点：无法纳入统一 UI、无法缓存、页面改版即失效。
- **产出要求**：`fetchIdentityCode` 返回 `IdentityCode(type=IMAGE_URL, content=页面URL)` 作为占位实现，UI 层对 IMAGE\_URL 类型走 WebView 展示。

### 方案 B：直调 mtop 接口（目标，抓包后实施）

1. 方案 A 的 WebView 中开启抓包（第 8 节），观察 home 页加载后发出的 mtop 请求；
2. 预期接口名形如 `mtop.taobao.*` 或 `mtop.cainiao.*`（**待抓包确认，此处不臆测**）；
3. 确认后：请求参数、响应中身份码字段（大概率是码内容字符串或图片 URL）→ 映射 `IdentityCode`；
4. 若返回码内容字符串 → 本地 ZXing 渲染（`CodeType.QR/BARCODE`）；若返回图片 URL → `CodeType.IMAGE_URL` 直接展示；
5. 记录 `expireAt`（响应中一般有时效字段）。

**验收口径**：方案 B 上线后替代方案 A；方案 A 代码保留作降级路径。

***

### 5.2 直调 mtop 接口（2026-09-27 已实测打通）

**身份码接口：`mtop.cainiao.pickup.identity.getIdentityMessage`（v=1.0，data=`{}`）**

```json
// 响应（脱敏）
{
  "api": "mtop.cainiao.pickup.identity.getidentitymessage",
  "data": {
    "data": {
      "accountName": "tb*******",
      "identityCode": "XNST10439687189437",
      "phone": "132****7330"
    },
    "ext": { "traceId": "..." }
  },
  "ret": ["SUCCESS::调用成功"]
}
```

实测结论：

- **每次调用返回不同的码**——身份码是纯动态的（前缀 XNST + 数字），没有 PDD 式的静态段结构，也不需要本地拼接算法；
- 码类型：XNST 是校园驿站前缀；普通驿站可能是其他前缀（待多站点验证）；
- 码内容为字符串 → 本地 ZXing 渲染 `CodeType.QR`（页面「放大」显示的是二维码）；
- 响应中无显式时效字段 → `expireAt` 按页面行为取短时效（页面有手动「刷新」，建议缓存 3\~5 分钟或跟随页面 180s 级别）；
- 登录后页面渲染的码即来自此接口（SSR 兜底 + 客户端刷新）。

### 5.3 相关接口（同一页面实测均 SUCCESS）

| 接口                                        | data                                                               | 用途                                            |
| ----------------------------------------- | ------------------------------------------------------------------ | --------------------------------------------- |
| `mtop.cainiao.pickup.identity.stGray`     | `{"stationType":"XY","stationCode":"POINT_0","scanTerminal":"ST"}` | 灰度配置                                          |
| `mtop.com.cainiao.pickup.user.get`        | `{"stationType":"XY"}`                                             | 用户信息（userId/mobile/openId/encryptPhone），账号信息源 |
| `mtop.cainiao.pickup.search.getTimeStamp` | `{}`                                                               | 服务器时间戳，**checkAlive 候选**                      |

### 5.4 实测踩坑（重要）

1. **自拼 URL 会被网关拒**：手拼 JSONP script / XHR fetch / 顶层导航直访 h5api（即使 sign 正确）均返回 `FAIL_SYS_ILLEGAL_ACCESS`；浏览器内经 lib-mtop 发出的请求正常。Node 直调**带全 cookie + Referer** 则可行（`test_live.js` 验证签名算法通过，`fetch_idcode.js` 全链路通过）；
2. Node 直调若报 `FAIL_SYS_SESSION_EXPIRED`：缺 httpOnly cookie（cookie2/skt 等）——用 3.4 纯协议登录即可拿到全套；
3. mtop 首调会报 `FAIL_SYS_TOKEN_EMPTY` 并 Set-Cookie 下发 `_m_h5_tk`，二调成功（7.2 节刷新机制的实例）。

***

## 6. 快递列表 / 取件码（✅ 2026-09-29 实测打通，含取件码）

bundle 静态分析已确认接口名与调用封装（`taobao/mtop_apis.txt`、`api_context.txt`）：

| 链路             | API                                                                          | data                                                                                                    | 状态                           |
| -------------- | ---------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- | ---------------------------- |
| **包裹列表（推荐主用）** | `mtop.cainiao.pickup.search.queryMultiStationPackages`                       | `{stationType:"XN"|"XY", pageNo:1, pageSize:10}`（两者均实测 SUCCESS，服务端按登录用户关联站点返回）                          | ✅ **实测 SUCCESS + 完整取件码**（2026-09-29，`taobao/js/test_list_query.js`） |
| **按运单号查/添加**   | `mtop.cainiao.pickup.search.multiOneSearch`                                  | 见 6.3                                                                                                   | ✅ **实测 SUCCESS**（2026-09-29） |
| 社区查件           | `mtop.cainiao.pickup.search.searchPackages`                                  | 精确入参未知（试 `{stationType,keyword,pageNo,pageSize}` 等均报 `FAIL_BIZ_pickup-0010`）                            | ⚠️ 仅关键字搜索场景需要                |
| 包裹详情           | `mtop.cainiao.pickup.query.logicdetail`                                      | `{stationId, stationType, mailNo, cpCode, bizVersion:"3.6"}`，`cpCode` 来自列表项 `upCpCode`                  | 接口名/参数已确认，响应待抓包              |
| 亮码/集合任务        | `mtop.cainiao.pickup.waybill.batchPartitionlightUp` / `createCollectionTask` | 待抓包（详情页实测见 `{stationCode, cabinetPackages:[{waybillNo,staOrderCode,gridNo,cbntNo,...}], timeStamp}` 形态） | ⚠️                           |

**业务链路（实测确认）**：`multiOneSearch`（搜索/添加包裹，响应中 `pickupCode` 脱敏为 `***`）→ 添加成功后再调 `queryMultiStationPackages`（快递列表）→ **列表项返回完整取件码**。实现 `fetchPickupCode` 时不要依赖 multiOneSearch 的取件码，必须走列表接口。

**queryMultiStationPackages 实测响应（2026-09-29，账号有 1 个包裹，脱敏）**：

```json
{ "data": {
    "allPkgSize": 1, "certainWaybillSize": 1, "suspiciousWaybillSize": 0, "noPackageNotice": false,
    "stationPackageAssembleList": [{
      "groupList": [{
        "groupTitle": "非分区", "groupType": "normal",
        "certainPickupWaybillList": [{
          "waybillNo": "79144370359463",
          "upCpCode": "ZTO",
          "receiverName": "倪", "receiverPhone": "172****8151", "receiverFullPhone": "172****8151",
          "pickupCode": "22-5-7603",           // ← 完整取件码（列表接口不脱敏）
          "desensitizedPickupCode": false,
          "privacyNumber": "5710",
          "stationId": "506974", "stationKey": "506974", "stationType": "YZ",
          "status": "3", "pickedWaybill": false,
          "packageType": "waybill", "pkgType": "normal",
          "nickName": "运单号添加", "addPackages": true, "addType": 1,
          "collectionTime": "2026-09-27 16:45:14", "collectionDay": "2026-09-27 16:45:14",
          "staOrderCode": "2104603585843205", "pkgRelationId": 1807511447,
          "returnReason": "其他原因退回", "shelfNo": "-1",
          "channel": "2", "allowRemovePkg": true
        }]
      }],
      "stationMessageDTO": {
        "stationId": "506974", "stationCode": "506974", "stationName": "珠海科技学院快递中心菜鸟驿站",
        "stationType": "YZ", "stationAddress": "广东省珠海市金湾区三灶镇珠海科技学院校园巴士站",
        "stationPhone": "0755-00000000", "officeTime": "周一至周日09:00-19:30",
        "lat": 22.04168, "lng": 113.40058, "ifQueryPickUpCode": true, "phoneSearch": true
      }
    }] },
  "ret": ["SUCCESS::调用成功"] }
```

- `XN` 与 `XY` 两种 stationType 入参返回完全一致（服务端按用户关联站点回 `stationType:"YZ"`），`fetchExpressList` 固定传 `XY` 即可；
- **status 状态枚举（2026-09-29 双样本实测确认）**：`"3"` = 待取件（在站）、`"4"` = 已取件/出库（79144370359463=待取 status 3；79033402727586=已取 status 4）。⚠️ `pickedWaybill` 与 `returnReason` 均不可作为状态依据（待取件样本也带 returnReason"其他原因退回"，且两接口的 pickedWaybill 恒为 false）；
- **列表接口只返回待取件包裹**（`allPkgSize`/`certainWaybillSize` 仅统计在站包裹，已取件的 79033402727586 不出现在列表中）。查已取件包裹需走 6.3 `multiOneSearch`；
- `collectionTime` 语义随状态变化：待取件时=入站时间（791 → 2026-09-27 16:45:14），已取件时=出库时间（790 → 2026-09-18 18:53:22）；
- `pickupCode` 格式 `区-架-号`；`ifQueryPickUpCode:true` 表示该站点支持取件码查询；
- 分页字段 `pageNo/pageSize` 生效性待多样本验证；
- Node 直调需带全 cookie（同 5.4 结论），`_m_h5_tk` 换取流程同 6.3。

抓包时机：登录态打开 2.2 `home-ssr-index` 翻页/筛选时抓 `searchPackages` 的 data 与响应。

### 6.1 快递列表（fetchExpressList）

**抓包目标**：登录态下打开 2.2 的 `home-ssr-index`（或手淘内驿站首页同源页面），抓「待取件包裹列表」mtop 接口。

预期响应字段（映射到 `ExpressItem`，以实际抓包为准）：

| ExpressItem 字段   | 预期来源           |
| ---------------- | -------------- |
| `id`             | `pkgRelationId`（取件码查询入参） |
| `station`        | `stationPackageAssembleList[].stationMessageDTO.stationName` |
| `stationAddress` | `stationMessageDTO.stationAddress` |
| `trackingNo`     | `waybillNo`    |
| `pickupCode`     | ✅ **已确认**：列表项 `pickupCode` 直接返回完整取件码（如 `22-5-7603`） |
| `summary`        | `nickName`（用户备注）/ 物品描述待样本补充 |
| `status`         | `status`（✅ 双样本确认："3"=待取件在站，"4"=已取件出库；列表仅返回 status=3） |
| `arrivedAt`      | `collectionTime` |
| `fetchDeadline`  | 待样本确认 |

- 分页：抓包时注意翻页参数（page/size 或 cursor），契约方法按 `page` 入参透传；
- 若接口按「站点」维度组织，站点列表接口一并抓下来，站点筛选用。

### 6.2 取件码（fetchPickupCode）

1. 列表已带 `pickupCode` 时，`fetchPickupCode` 直接从 `ExpressItem` 组装返回（允许实现层做本地合并，无需再请求）；
2. 列表不带或需详情时，抓包 `package-detail` 页面的 mtop 接口，按 `expressId` 查询；
3. 若支持扫码取件，响应中找二维码内容 → `PickupCode.qrContent`。

### 6.3 按运单号查询（2026-09-29 实测打通）

**接口：`mtop.cainiao.pickup.search.multiOneSearch`（v=1.0）** —— 「添加包裹」搜索框的真实接口。

入参抓包来源：内置浏览器登录态打开 2.2 `package-search-new` 页（注意真实页面 URL 是 `pages.tmall.com/wow/z/uniapp/1011717/last-mile-fe/end-collect-platform/package-search-new`，SSR 壳 exparams 里的 req_url 可拿到），输入运单号点击「搜索并添加」，从 JSONP 请求抓到 data：

```json
{
  "stationType": "XN",
  "source": "DEFAULT",
  "traceId": "1790689938645-162c7b9889d3dd",
  "searchValue": "79144370359463",
  "stationCode": "POINT_0",
  "searchKey": "ST_1790690603298_POINT_0_757"
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `searchValue` | **运单号（关键字段）**——此前试 `waybillNo`/`mailNo`/`keyword` 均报 `FAIL_BIZ_waybill-5047`，正确字段是 `searchValue` |
| `stationType` | `XN`（校园驿站）/ `XY`（普通驿站） |
| `source` | `DEFAULT` |
| `traceId` | 自造：`{毫秒时间戳}-{随机串}` |
| `stationCode` | 浏览器抓包为 `POINT_0`，Node 直调传 `POINT_0` 亦成功（服务端按登录用户关联站点返回） |
| `searchKey` | 自造：`ST_{毫秒时间戳}_{stationCode}_{0-999随机数}` |

实测响应（脱敏）：

```json
{
  "api": "mtop.cainiao.pickup.search.multionesearch",
  "data": {
    "data": {
      "addpackage": false,
      "pickedWaybill": false,
      "pkgResultResps": [{
        "phone": "1*2**4*8151",
        "certainPickupWaybillList": [{
          "waybillNo": "79144370359463",
          "upCpCode": "ZTO",
          "receiverName": "倪",
          "receiverPhone": "172****8151",
          "pickupCode": "***",
          "privacyNumber": "5710",
          "stationId": "506974",
          "unionCode": "506974",
          "status": "3",
          "packageType": "waybill",
          "nickName": "运单号添加",
          "pickedWaybill": true,
          "collectionTime": "2026-09-27 16:45:14",
          "channel": "2"
        }]
      }],
      "searchOnePackage": 0,
      "searchType": "03"
    }
  },
  "ret": ["SUCCESS::调用成功"]
}
```

实测结论：

- Node 直调成功（复现脚本 `taobao/js/test_multionesearch.js`）：token 换取流程同 5.4 第 3 条（首调 `FAIL_SYS_TOKEN_EMPTY` → set-cookie 下发 `_m_h5_tk` → 带新 token 重签重试即 SUCCESS）；
- `h5api.m.taobao.com` 与 `h5api.m.tmall.com` 两个网关均可用（页面实际走 tmall 网关）；
- Referer 用 `https://pages.tmall.com/`；
- 响应要点：`pkgResultResps[].certainPickupWaybillList[]` 为命中的包裹；`status:"3"` + `pickedWaybill:true` 表示已取出（待取件时预期 status 不同，待补样本）；`pickupCode` 脱敏为 `***`，`privacyNumber` 尾号 4 位；`upCpCode` 为承运商编码（ZTO=中通）；
- `addpackage:false` 表示纯查询；页面「搜索并添加」流程在命中后会再走 add 相关接口（入参待抓，暂不需要）。

**已取件样本对比（79033402727586，2026-09-29 实测）**：multiOneSearch 对已取件包裹返回 `status:"4"`、`pickupCode:"***"`（脱敏）、`collectionTime:"2026-09-18 18:53:22"`（出库时间）、`allowRemovePkg:false`，且不出现在 `queryMultiStationPackages` 列表中。⚠️ multiOneSearch 顶层 `pickedWaybill` 不可靠（待取件 791 曾返回 true、已取件 790 反而为 false），状态判断一律以 `status` 为准（3=待取，4=已取）。

***

## 7. MTOP H5 签名机制（淘宝系接口核心，必须吃透）

淘宝 H5 页面数据全部走 MTOP 网关，直调时需按以下机制签名（开源社区公开资料 + 需抓包核对）：

```
请求：GET/POST https://h5api.m.taobao.com/h5/{api}/{version}/?appKey=...&t=...&sign=...&api={api}&v={version}&...&data={json}

token   = Cookie "_m_h5_tk" 下划线前的 32 位 hex（如 "abc123..._1700000000000" 取 "abc123..."）
t       = 当前毫秒时间戳（与 URL 参数 t 一致）
appKey  = H5 常见为 12574478（以抓包实际值为准）
data    = 业务参数 JSON 字符串（与 URL 参数 data 完全一致）

sign    = MD5( token + "&" + t + "&" + appKey + "&" + data )  → 32 位小写 hex
```

**已实测确认（2026-09-27）**：appKey 固定 `12574478`（lib-mtop 源码 + 线上请求双确认）；页面统一走 `lib-mtop`（页面实载 2.6.1，官方源 2.7.3 通用），`H5Request:true`，**无任何自定义加密**（对比 PDD anti-content 简单得多）；Node 复现脚本 `taobao/js/test_live.js`（签名验证）、`fetch_idcode.js`（全链路）。

**token 过期与刷新**：

- 返回 `FAIL_SYS_TOKEN_EXOIRED` 时，响应的 `Set-Cookie` 会下发新的 `_m_h5_tk`；
- 刷新流程：用新 token 重算 sign 重试一次（最多重试 1 次）；
- 因此 `MtH5Signer` 要做成「自动读 CookieManager 最新 `_m_h5_tk` → 签名 → 失败刷新重试」。

**实现要求：**

- 新建 `TaobaoMtopClient`（模块内私有）：统一封装 URL 拼接、sign 计算、Cookie 注入、token 刷新重试、`ret` 错误码归一（→ 4.4 的 `PlatformError`）；
- `MtH5Signer.sign(token, t, appKey, data)` 必须有单测（给定输入断言 MD5 输出）；
- Cookie 注入方式：OkHttp 拦截器从 `android.webkit.CookieManager` 读 `.taobao.com` cookie 拼进 `Cookie` header（WebView 和 OkHttp 的 cookie 库不共享，必须手动桥接）。

***

## 8. 抓包任务清单（开工第一周完成）

**工具**：真机 + Charles（或 HttpCanary/mitmproxy），抓包对象是登录后的淘宝 H5 页面。

| # | 抓什么       | 操作                                              | 归档到                                  |
| - | --------- | ----------------------------------------------- | ------------------------------------ |
| 1 | 登录过程      | WebView 走完一次登录，记录登录页 URL 是否可用、成功跳转链             | `docs/capture/taobao/login/`         |
| 2 | 身份码接口     | 打开 2.1 home 页，抓页面加载后的全部 `h5api.m.taobao.com` 请求 | `docs/capture/taobao/identity-code/` |
| 3 | 快递列表接口    | 打开 2.2 home-ssr-index（或手淘驿站页），抓列表接口 + 翻页 + 站点切换 | `docs/capture/taobao/express-list/`  |
| 4 | 取件码接口     | 打开 package-detail，抓详情接口                         | `docs/capture/taobao/pickup-code/`   |
| 5 | appKey 确认 | 从抓包请求里确认 mtop 的实际 appKey 与公共参数                  | 同上                                   |

**归档要求**：每个接口存 `请求URL + 请求头 + data 参数 + 响应 JSON` 一个文件；**脱敏**——删除/替换真实 `unb`、昵称、手机号、cookie 值、签名值（保留格式）。

***

## 9. 已知风控点与注意事项

1. **UA**：请求 UA 对齐手机淘宝/淘宝 H5；WebView 默认 UA 可能触发降级或风控；
2. **频率**：对同一 mtop 接口加最小间隔（建议 ≥3s/次，自动刷新场景叠加随机抖动），过频会触发 `FAIL_SYS_LIMIT` 类错误；
3. **Cookie 完整性**：mtop 请求必须带齐 `cookie2`、`_m_h5_tk`、`_m_h5_tk_enc`、`unb`、`sg` 等整套，只带部分可能被判异常会话；
4. **页面版本**：TinyApp 页面带 version（manifest 可见），接口名/版本可能随版本更替，解析器必须对未知字段容错（缺字段不 crash，填 null 并 `ParseFailed` 上报日志）；
5. **禁止**：日志打印 Cookie/token；把真实凭据提交进仓库；频繁重登（一天多次登录风控敏感）；
6. 本模块仅供个人学习自用，遵守总规划第 9 节合规声明。

***

## 10. 建议开发步骤

1. 建模块 `:platform:taobao`，实现 `TaobaoAdapter` 空壳（所有方法抛 `ParseFailed` 占位），Hilt 注册跑通；
2. `loginUrl()` + `detectLoginSuccess()` 联调 LoginWebView → 登录落盘（验收 1、2 项）；
3. 实测 3.1 候选登录页，固化 URL，更新本文档；
4. 实现 `TaobaoMtopClient` + `MtH5Signer` + 单测；
5. 抓包（第 8 节），归档 mock 数据；
6. `checkAlive` → `fetchExpressList` → `fetchPickupCode` → 身份码方案 A → 方案 B（按 4/5/6 节顺序）；
7. 全部异常路径归一 `PlatformError`；
8. 跑验收清单（第 11 节），提 PR。

## 11. 验收清单（淘宝专项）

- [ ] WebView 登录 10s 内捕获凭据并落盘；重复登录幂等
- [ ] 杀进程重开，checkAlive 通过（token 刷新重试生效）
- [ ] 身份码展示正常（方案 A 可用 → 方案 B 直调接口成功）
- [ ] 快递列表字段与手淘驿站页面一致，翻页正常
- [ ] 取件码查询正常
- [ ] 篡改凭据后各接口返回 `NeedRelogin`，UI 标红
- [ ] `MtH5Signer`、解析器单测通过
- [ ] `docs/capture/taobao/` mock 数据齐全且已脱敏
- [ ] PR 带验收截图

***

*文档版本：v1.3 ｜ 2026-09-29 实测更新（登录/身份码/checkAlive/快递列表 queryMultiStationPackages/按运单号查询 multiOneSearch 全链路打通；取件码走列表接口获取）｜ 契约变更请同步更新总规划 README 第 4 节*
