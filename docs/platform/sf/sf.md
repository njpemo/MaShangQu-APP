# 顺丰模块开发文档（短信登录 + MIMP 本地身份码路线，✅ 已脱离 YYB）

> 对应总规划：[../../../README.md](../../../README.md) 第 4 节「核心契约」、第 7 节「平台模块开发规范」。阅读前先读 4.1/4.3/4.4。
>
> 任务模块：`:platform:sf` ｜ 实现类：`SfAdapter` ｜ 分支：`platform/sf`
> **登录模式变更（2026-09-29）**：~~`loginMode = REMOTE_SERVICE`（YYB-GO）~~ → **SMS_CODE（手机号+短信验证码）**。YYB-GO 已退役，详见第 2.4/2.5 节与 4.4 节。

> **逆向状态：✅ 核心链路已全部跑通**（2026-09-26）。登录（code→token）、签名算法、身份码结构、快递列表/取件码接口均已逆向并实测验证。参考实现：`D:\身份码聚合app\yshoufa-client.js`（Node，可跑通 code→token→身份码→快递列表全链路）。本文档第 1.5、2.2、4.2、4.3、5、9 节已回填实测结论。
>
> **重大更新（2026-09-29）：身份码脱离 YYB 全链路打通并实测**——改走官方「顺丰速运」小程序体系（ucmp/MIMP），短信验证码登录一次即可拿到身份码**静态段**，此后**永久本地离线生成**（伪动态结构，见 4.3）。Node 端到端验证脚本：`D:\身份码聚合app\sf-identity-e2e.js`（A/B 双路 200 出码成功，签名对拍 100% 吻合）。旧驿收发（ant.yshoufa.com）链路降级为参考留档。

***

## 0. 任务目标

| 能力    | 对应契约方法                | 路线                   | 状态                               |
| ----- | --------------------- | -------------------- | -------------------------------- |
| 登录态获取 | `remoteLogin()` | ~~YYB-GO~~ → **短信验证码登录（2.5 节）** | ✅ 已切换（YYB-GO 退役）         |
| 登录态校验 | `checkAlive()`        | 本地静态段常驻后基本免校验       | 简化（见 4.4）                      |
| 身份码   | `fetchIdentityCode()` | MIMP createIdentityCode（4.4 节） | ✅ 已切换（静态段一次获取，永久本地生成）  |
| 快递列表  | `fetchExpressList()`  | 小程序接口                | ✅ 已实测（searchWaybill 直出取件码，见 5.1） |
| 取件码   | `fetchPickupCode()`   | 小程序接口                | ✅ 已实测（列表直出 + 运单号精查，见 5.2）        |

**开发策略**：~~逆向工作流~~（已完成）→ YYB-GO 登录联调 → checkAlive → 业务接口逐个接入。

***

## 1. 背景知识（先读）

1. **取件场景**：顺丰派件到顺丰驿站/服务点后，取件人在「顺丰速运」微信小程序（appId `wx9aa8425de154698d`，内部代号 `yjy-c-wchat`，运营方"驿收发"）内查看收件与取件信息、出示取件码/身份码。我们走**微信小程序**路线。
2. **接口网关（已确认）**：`https://ant.yshoufa.com`。微服务前缀划分：
   - `/e-mini/` 小程序业务（登录、身份码、取件、寄件）
   - `/e-uc/` 用户中心（用户信息）
   - `/e-order/` 订单（寄件单、运单）
   - `/e-warehouse/` 仓储出库（取件码）
   - `/e-system/` 字典/公告、`/e-beacon/` 广告埋点、`/e-station/` 驿站信息、`/e-storage/` COS 文件
   - 测试/UAT：`anttest.yshoufa.com` / `antuat.yshoufa.com`
3. **官方开放平台不可用**：顺丰开放平台（open.sf-express.com）面向签约商家（B2B 寄件），个人取件场景无接口，只能走小程序逆向。已验证小程序路线可行。
4. **鉴权形态（已确认）**：JWT（HS256），header 名 `YJY-Auth`。payload 含 `userId / openId / mobile / exp / jti` 等；**有效期约 24 小时**（实测 token `exp - iat ≈ 85230s`）。签名体系见第 9 节。
5. **小程序技术栈（解包确认）**：原生小程序 + mobx + crypto-js；身份码页为 webview 嵌 H5（`/yshoufa-h5-c/`，Vue2 + vant + JsBarcode）。解包产物位于 `D:\身份码聚合app\sf\wx9aa8425de154698d_unpacked\`（仅本地留存）。

***

## 2. 登录方案（REMOTE\_SERVICE）

### 2.1 契约实现方式

```kotlin
@Singleton
class SfAdapter @Inject constructor(...) : PlatformAdapter {
    override val platform = Platform.SF
    override val loginMode = LoginMode.REMOTE_SERVICE
    override fun loginUrl(): String = ""          // 空实现
    override fun detectLoginSuccess(...) = null   // 空实现
    override suspend fun remoteLogin(): Credential? = yybGoClient.login(Platform.SF)
    ...
}
```

### 2.2 登录链路（✅ 已逆向并实测）

```
wx.login() 取 code（一次性，5 分钟有效，仅微信客户端可生成）
→ POST https://ant.yshoufa.com/e-mini/mini/login
   body: {"code": "<wx.login code>", "appid": "wx9aa8425de154698d"}
   headers: 签名三件套 + Content-Type（见第 9 节，登录接口无需 YJY-Auth）
→ 响应: {"success":true,"code":"200","data":{
     "accessToken":"<JWT>",   // 即 YJY-Auth，有效期约 24h
     "userId":"...", "mobile":"...", ...
   }}
```

- 假 code 实测返回 `e-mini_200_P_104` + 微信原始错误 `40029 invalid code` → 请求真实到达微信侧，链路完整；
- 同账号重复登录签发新 token（jti 变化），旧 token 在 exp 前仍有效；
- 登录响应同时返回 `mobile`，可用于判定是否已完成手机号绑定。

### 2.3 凭据复用策略（降低 YYB-GO 依赖）

- token 有效期 \~24h：App 本地凭据有效期内**绝不**重复调 YYB-GO；提前 2h 视为临期刷新（JWT exp 可本地解码，无需网络探测）；
- 每次业务请求前先 `checkAlive()`（第 4.1 节），失效才调 `remoteLogin()` 刷新；
- YYB-GO 不可达时：凭据还有效 → 正常跑；凭据已失效 → UI 标「登录服务不可用」，不 crash。

### 2.4 新路线：官方「顺丰速运」小程序短信验证码登录（研究中的去 YYB 方案）

> 2026-09-29 新增。目标平台：**官方顺丰速运小程序**（appId `wxd4185d00bf7e08ac`，网关 `https://ucmp.sf-express.com`），与驿收发（2.2 节）是**两套独立体系**。身份码页面与驿收发一致，身份码体系同一套（该包内出现 identityCodeSignConf 可证）。此路线**不依赖 wx.login，可完全脱离 YYB-GO**。

#### 2.4.1 已逆向结论（解包 `D:\身份码聚合app\sf\wxd4185d00bf7e08ac_unpacked\`）

**签名算法**（config/sign.js，混淆已解，Node 直跑验证）：签名放 header 三件套 `appId` / `sign` / `nonceStr`：

```
sign = MD5("appId=<appId>&nonceStr=<32位随机串>&requestBody=<JSON.stringify(data)>&key=<key>").toUpperCase()
// GET 无 body 时去掉 requestBody 段: MD5("appId=..&nonceStr=..&key=..")
```

| 用途                           | appId                  | key                                |
| ---------------------------- | ---------------------- | ---------------------------------- |
| 通用 commonSignConf            | `wxapp-valid-0328`     | `2b08f7f6bf564a1dada1570535fd44ba` |
| 员工信息 empInfoSignConf         | `wxapp-emp-ce3a59c378` | `977e13b6834c437686ab58d8a3fa7c7e` |
| **身份码 identityCodeSignConf** | `authenticationRnApp`  | `NhMJLZ8F`                         |

**基础登录**（app.js `login()`）：`wx.login` code →
`GET https://ucmp.sf-express.com/wxaccess/weixin/appOnLogin?code=<code>&publicId=gh_f9d9fca26a50`（带签名头）
→ 响应 `{sessionId, openid}`。**session 有效期约 1700 秒（<28 分钟）**。

**登录态传递**（service/request/aletr.js `getCustomHeaders`）：后续请求 header `suuid: <sessionId>`，另有 `msgid`=WA+UUID、`wxapp-version`=V17.71、`envVersion`、`deviceId`、`grey-mobile-rem`。**无 Cookie / 无 YJY-Auth，suuid 即登录态**。

**短信发码**（service/api/common/index.js `getSmsReq`）：
`GET /wxopen/login/code?mobile=<手机号>`。风控触发极验时带 header：
`gt-lot-number` / `gt-captcha-output` / `gt-pass-token` / `gt-gen-time` / `gt-scene: WXAPP-LOGIN`；
极验 captchaId = `fadae7813ed2b90d59031c524f809095`（config/env.js loginCaptchaId）。

**短信登录提交**（✅ 2026-09-29 抓包确认）：

```
① 发码  POST /wxopen/login/code?mobile=<手机号>   body {}
   headers: 签名三头(appId/sign/nonceStr, commonSignConf) + suuid(匿名session)
            + 极验四件套 gt-lot-number/gt-captcha-output/gt-pass-token/gt-gen-time + gt-scene:WXAPP-LOGIN
   签名已对拍验证：sign=MD5("appId=..&nonceStr=..&requestBody={}&key=..").toUpperCase() ✅ 100%吻合
② 登录  POST /wxopen/weixin/bindMember?mobile=<手机号>&code=<6位短信码>&isSynAddress=check&sign=&regSource=WXAPP   body {}
   ⚠️ 无签名三头（plain 请求）；query 里 sign= 为空串；带同一 suuid
   响应: obj.userBound {memno, userId, mobile, isBind:"Y"} — 不换 sessionId
③ 验证  POST /wxopen/weixin/wxMemIsBind   body {}   签名三头 + suuid
   响应: obj {memId, memNo, mobile, wxid}
```

**登录态模型（抓包确认）**：suuid 全程不变 = appOnLogin 返回的匿名 sessionId；bindMember 成功后该 session 即绑定会员 → **登录态 = 同一个 suuid**。未登录也发码成功（匿名 suuid 即可）。

**其他可用端点**：`/wxopen/changeMobile/validate`(POST 发码校验)、`/wxopen/weixin/bindMiniProgramMobile`、`/wxopen/weixin/checkCode`、`/wxopen/cryptMobileLoginStatus`、`/wxopen/bindMemberByEncryptMobile`、`/wxopen/wxMemIsBind`。

#### 2.4.2 Node 复现路径（✅ 2026-09-29 全部实测完成）

```
① 发码  POST /wxopen/login/code?mobile=..   实测必须带极验四件套（gt pass_token 一次性，防重放）
        → App 落地方案：WebView 嵌极验页人工过一次滑块，截获 gt-*（同 PDD anti-content 思路）
② 登录  POST /wxopen/weixin/bindMember?mobile=..&code=..&isSynAddress=check&sign=&regSource=WXAPP
        （无签名头）→ suuid 即登录态
③ suuid → MIMP Cookie 置换 → createIdentityCode 出静态段（见 4.4）→ 永久本地生成
```

Node 端到端脚本：`sf-identity-e2e.js`（A=抓包 Cookie 直调、B=suuid→Cookie 置换全链自取，双路 200 出码）。

#### 2.4.3 与 2.2 驿收发路线的取舍（✅ 已定论：短信路线胜出）

| <br /> | 2.2 驿收发（YYB-GO，已弃用）   | 2.4 官方顺丰速运（短信）            |
| ------ | ------------------------ | ----------------------------- |
| 依赖     | YYB 服务 + wx.login code   | 手机号 + 短信码（发码需极验一次）            |
| 凭据时效   | JWT \~24h                | suuid \~28min；但**静态段永久有效**，登录一次即可 |
| 状态     | ✅ 已跑通（留档兜底）              | ✅ 全链路实测打通                     |
| 结论     | ~~主路线~~ → 退役             | **新主路线**（身份码场景零服务端依赖）          |

#### 2.5 最终登录链路（✅ 实测定稿）

```
①发码  POST https://ucmp.sf-express.com/wxopen/login/code?mobile=<手机号>  body {}
      headers: appId/sign/nonceStr(appId=wxapp-valid-0328,key=2b08f7f6bf564a1dada1570535fd44ba)
               + suuid + gt-lot-number/gt-captcha-output/gt-pass-token/gt-gen-time + gt-scene:WXAPP-LOGIN
      （极验 captchaId=fadae7813ed2b90d59031c524f809095，App 内 WebView 人工过滑块取 gt 四件套）
②登录  POST https://ucmp.sf-express.com/wxopen/weixin/bindMember
        ?mobile=<手机号>&code=<短信码>&isSynAddress=check&sign=&regSource=WXAPP   body {}
      headers: suuid（发码时用的匿名 session）——无需签名三头
      成功: obj.userBound {isBind:"Y", memno, userId}
③（可选校验）POST /wxopen/weixin/wxMemIsBind  签名三头+suuid → obj.memId/memNo
```

- **登录态 = suuid（约 28 分钟）**，但对身份码场景足够：登录一次 → 4.4 节拿静态段 → 此后永久离线出码；
- 会话过期重新走 ①② 即可（需再过一次极验）；
- 跨体系实测结论：suuid **不能**调驿收发 e-mini 接口（401），两套体系仅在「静态段」上同源。

***

### 2.6 最终方案：MIMP H5 独立短信登录（✅ 2026-09-29 全链实测出码，App 已落地，彻底去 YYB/ucmp）

> §2.4/§2.5 的 ucmp 链路已废弃：`appOnLogin` 只认 wx.login 真 code（假 code ERR500），`bindMember`
> 强依赖活 suuid（无/随机/过期全报 errorUserSession）→ 纯短信登录在 ucmp 侧是死结。
> 突破口：MIMP H5 有**独立的手机验证码登录页**（`https://mcs-mimp-web.sf-express.com/login`），
> 与 ucmp 会话零耦合。浏览器全链实测：sendValidCode → 短信码 → loginByMobile → createIdentityCode
> 出码 `150610521ACS2853414300`（静态段与驿收发一致）。Node 参考 `sf/sf-mimp-e2e.js`。

```
① 发码  POST https://mcs-mimp-web.sf-express.com/mcs-mimp/sendValidCode
   body: {"sendMobile":"<手机号>","captcha_args":{"lot_number":..,"captcha_output":..,"pass_token":..,"gen_time":..}}
   headers: timestamp/signature/sysCode=MCS-MIMP-CORE/channel=weixin/platform=OTHER
   （signature 同 §4.4 固定密钥公式；缺 captcha_args → {"errorCode":"700001","errorMessage":"未进行滑块验证"}）
   极验 v4 captchaId = d6ee898c223af2b2374447d5af25d51b（MIMP H5 专用，与小程序 fadae... 不同，
   四件套绑定 captcha_id 不可混用；pass_token 一次性）
② 登录  POST /mcs-mimp/loginByMobile
   body: {"channel":"WEIXIN","mobile":"..","validCode":"<短信码>","from":"weixin"}
   → Set-Cookie 落 MIMP 会话（sessionId/JSESSIONID/_login_user_id_/_login_mobile_）
③ 出码  POST createIdentityCode（同 §4.4，Cookie 换成 ② 的会话）
```

**App 落地**（2026-09-29）：`SfMimpClient.kt`（MIMP 直连三接口）+ `SfAdapter`（token=MIMP Cookie 串，
extra 落 mimp="1"/mobile/staticSeg）+ `sf_geetest.html` captchaId 已换 d6ee...。
凭据寿命：静态段永久；MIMP session >2h 实测（静态段到手后不再需要会话）。

***

## 3. 小程序逆向工作流（✅ 已完成，留档方法论）

1. **解包**：PC 微信打开「顺丰速运」小程序点遍所有功能页 → `WeChat Files/Applet/<appId>/` 取 `.wxapkg` → [wux1an/wxapkg](https://github.com/wux1an/wxapkg) 解包；
2. **源码分析**（编译产物 `app-service.js` 为所有页面+工具的合并 JS，可读）：
   - `utils/env.js` → 网关域名 + `secretKey`（test/uat/prod 三套）
   - `utils/sign.js` → 签名算法（见 9.1）
   - `utils/api/http.js` → 请求头组装、错误码处理、AES 解密响应（`iYsM5rKhgq2NNoMX` + k/v 字段）
   - `utils/api/user.js`、`utils/api/idCode.js` → 登录与身份码接口
   - `pages/mine/idCode/` → 身份码页（webview 嵌 H5）
3. **抓包验证**：PC 微信 + Charles/Fiddler；⚠️ 一律用微信小号 + 专用设备；
4. **产出回填**：本文档 2.2/4.2/5/9 节已回填。

***

## 4. checkAlive 与身份码

### 4.1 checkAlive

复用业务接口探测：`GET /e-uc/user/v2/loginUserInfo`（轻量、无副作用），按响应判断：

- `success:true` → true；401 或登录态失效错误码 → false（映射 `NeedRelogin`）；
- 结果缓存 5 分钟（`PlatformLoginState.lastCheckAt`）；JWT 本地 exp 过期可直接判定 false，省一次请求。

### 4.2 身份码获取（fetchIdentityCode）✅ 已跑通

```
POST https://ant.yshoufa.com/e-mini/mini/credentialPerson
body: {}
headers: 签名三件套 + YJY-Auth
→ {"success":true,"code":"200","data":"150409083ACS2853414300"}
```

- `data` 即身份码字符串，App 侧用 **CODE128 条形码**渲染（H5 原版用 JsBarcode，`displayValue:false`，码号文字单独显示在下方）；
- 放大/缩小操作会触发一次刷新重取；
- 采集自 H5 页面：刷新周期 3 分钟（`setInterval(createCode, 180000)`）。

### 4.3 静态身份码研究专项（✅ 结论：伪动态，结构已破解）

**码结构**：`{时间秒计数}{ACS}{用户静态段}`

| 段         | 示例           | 规律                                                                                                              |
| --------- | ------------ | --------------------------------------------------------------------------------------------------------------- |
| 时间段（9 位）  | `150409083`  | = `当前Unix秒(秒级) − 1640010000`，线性 +1/秒，无加密。纪元 1640010000 ≈ 2021-12-20 23:40 (UTC+8)                               |
| 固定标识      | `ACS`        | 恒定                                                                                                              |
| 静态段（10 位） | `2853414300` | **与用户永久绑定**，跨 8 分钟/多个刷新周期不变；md5/sha1(userId/手机号) 等推导均不匹配 → 服务端存储的用户标识（推测为顺丰内部客户编码）。唯一获取途径即 credentialPerson 返回值 |

三样本验证（间隔 139s / 335s）：时间段与真实时间差**精确吻合**，静态段恒定。

**结论三分类落位**：属「服务端动态签发」但实际是**伪动态**——时间段可离线计算，安全完全依赖静态段保密。**落地方案**：首次调 credentialPerson 拿到静态段后缓存，App 本地按公式生成任意时刻的身份码，**无需每次请求**（仍建议按 3 分钟周期真实请求一次对齐服务端，防止服务端有额外校验逻辑）。

**风险提示**：静态段泄露 = 身份码可被离线伪造。凭据加密落盘（总规划第 7 节）覆盖此风险。

### 4.4 新出码通道：MIMP createIdentityCode（✅ 2026-09-29 全链路实测，YYB-free 主路线）

校园专享页「身份码」入口（需定位授权才显示）实际调用 MIMP 体系接口，**返回与驿收发 credentialPerson 同一个身份码**（静态段逐位一致）：

```
前置：2.5 节短信登录得到 suuid
① Cookie 置换（Node/OkHttp 均可，跟随 302 即可）
   GET https://ucmp.sf-express.com/wechat-act3/weixin/activity/sfnewactivity
       ?bizCode=<urlencode({"path":"/campusZoneId","supportShare":"YES","identifySucc":"2","from":"xszx03"})>
   headers: suuid
   → 302 链（activityRedirect 带 unionId 票据 → mcs-mimp-web /campusZoneId）
   → 每跳收 Set-Cookie，最终得到 mcs-mimp-web 会话：
     sessionId=JSESSIONID / _login_user_id_=<memId> / _login_mobile_=<手机号>
② 出码
   POST https://mcs-mimp-web.sf-express.com/mcs-mimp/commonPost/~memberManage~studentService~createIdentityCode
   body: {}
   headers:
     timestamp: <毫秒时间戳>
     signature: md5("token=wwesldfs29aniversaryvdld29&timestamp=<同上毫秒>&sysCode=MCS-MIMP-CORE")  // 固定密钥硬编码于 MIMP 前端 app.js，已对拍验证
     sysCode: MCS-MIMP-CORE
     channel: xszx03
     platform: MINI_PROGRAM
     Cookie: <①的完整 cookie>
     Referer: https://mcs-mimp-web.sf-express.com/origin/a/mimp-member-right/campusZoneId
   → {"success":true,"obj":"150604972ACS2853414300"}
```

**架构定论**：
- `obj` 即身份码字符串，结构与 4.3 完全一致（`Unix秒−1640010000 + ACS + 静态段`）；
- **静态段永久有效 → 登录一次、取一次静态段，之后 App 永久本地生成**，日常零服务端调用、零 YYB；
- MIMP session（JSESSIONID）实测 >2h 仍有效（准确上限待测），但身份码场景不依赖其常驻；
- 鉴权体系：MIMP 签名密钥固定 + Cookie 会话，与驿收发三件套签名（第 9 节）互不通用；
- 附带发现：`~studentService~queryStudentIdentifyInfoNew` 可查学生认证信息（identifyChannelId/schoolName/expirationDate），MIMP 体系可出学生专属码，驿站场景后续可扩展。

***

## 5. 快递列表 / 取件码（✅ 已实测回填）

### 5.1 快递列表（fetchExpressList）✅

```
POST https://ant.yshoufa.com/e-mini/mini/searchWaybill
body: { "pageNum": 1, "pageSize": 10, "scanFlag": false, "state": "wait_out_warehouse"(可选) }
→ { success, data: { records: [...], total, size, current } }
```

- `state` 筛选：不传=全部；`wait_out_warehouse` 待取件；`already_out_warehouse` 已出库（已取走）
- 实测响应记录（单条，字段全量）：

```json
{
  "billCode": "SF5158042782849",
  "stationId": "5007948858040930198",
  "stationName": "科技学院校园店",
  "inTime": 1788999508202, "outTime": 1789026850566,
  "moveTime": null, "checkTime": null,
  "expressBrandCode": "SF", "expressBrandName": "顺丰",
  "shelfCode": "S-1-2",
  "pickupCode": "S-1-2-4018",
  "customerMobile": "13800000000", "customerMobileSource": "sf",
  "state": "already_out_warehouse",
  "relativeMobile": null, "relativeName": null,
  "reservationFlag": false, "subscribeType": null, "reservationAddress": null,
  "deliveryMode": null, "privacyPickCodeSwitch": null,
  "remark": null,
  "quickGrabFlag": false,
  "operateImageKeys": ["/waybill_in/SF...-in_warehouse-....jpg"],
  "supportRobotDelivery": false, "canRobotBooking": false, "robotBookingRejected": false
}
```

映射 `ExpressItem`（✅ 全部字段确认）：

| ExpressItem 字段                | 来源                                                                           |
| ----------------------------- | ---------------------------------------------------------------------------- |
| `id`                          | `billCode`                                                                   |
| `station`                     | `stationName`                                                                |
| `trackingNo`                  | `billCode`                                                                   |
| `pickupCode`                  | `pickupCode`（**列表直出，无需二次查询**）                                                |
| `summary`                     | 无对应字段（接口不提供物品描述）                                                             |
| `status`                      | `state`：`wait_out_warehouse`→待取件；`already_out_warehouse`→已取走；辅以 `outTime` 判定 |
| `arrivedAt` / `fetchDeadline` | `inTime` / 无 fetchDeadline 字段（驿站无最迟取件时间概念）                                   |

补充字段：`shelfCode` 货架位、`operateImageKeys` 入库照片（经 `/e-storage/cos/getCosDownloadUrl` 换取下载地址）、`remark` JSON（丰巢柜场景含 `cabinetAddress`）。

辅助接口（按需）：`POST /e-order/order/mini/page`（寄件单分页）、`/ant-station/station/getStationInfoByCode`（驿站详情）。

### 5.2 取件码（fetchPickupCode）✅

**主路径**：searchWaybill 已直出 `pickupCode`，本地组装返回，不再发请求。

**按运单号精查**（他人代查场景）：

```
POST /e-mini/warehouse/getWaybillInfo              {billCode, stationId}
POST /e-mini/warehouse/v2/getWaybillPickupCode     {billCode, mobileSuffix, stationId}
```

实测行为：

- `getWaybillInfo`：`stationId` **必填**（缺失 → `ant-escrow_203_P_000`「请输入参数:stationId」）；stationId 可从 searchWaybill 列表获取；返回单条详情含 `pickupCode`，无需尾号验证；
- `v2/getWaybillPickupCode`：`mobileSuffix` = 收件手机后 4 位，**错误尾号 →** **`e-mini_1000_B_100`「手机号错误或查询不到取件码」**（防遍历设计）；正确尾号 + 待取件 → 返回 `pickupCode`；已取走 → `e-mini_1000_B_101`「该包裹已取走」；
- 运单号不存在 → `e-mini_1000_B_102`「运单号有误，请仔细核对」。

### 5.3 校园专享 H5 待取快递列表（MIMP queryStudentWaittingWaybill）✅ 2026-09-30 全链实测

校园专享页「待取快递」tab 的列表接口，**取件码直接在列表项返回，无需单独详情接口**。与 4.4 同属 MIMP 体系（`mcs-mimp-web` 域 + 固定密钥签名 + Cookie 会话）。

```
POST https://mcs-mimp-web.sf-express.com/mcs-mimp/commonPost/~memberManage~studentService~queryStudentWaittingWaybill
body: {}                                  // 空对象，无分页/筛选参数
headers:
  Content-Type: application/json
  timestamp: <毫秒时间戳>
  signature: md5("token=wwesldfs29aniversaryvdld29&timestamp=<同上毫秒>&sysCode=MCS-MIMP-CORE")  // 同 4.4，固定密钥，已二样本 100% 对拍
  sysCode: MCS-MIMP-CORE
  channel: weixin                         // 校园 H5 页内实测值
  platform: OTHER                         // 同上
  Cookie: <4.4 ① 的 mcs-mimp-web 会话 cookie>   // HttpOnly，浏览器/Node 均需带全
→ 有数据: {"success":true,"date":"...","obj":[ <列表项>, ... ]}
→ 空态:   {"success":true,"date":"...","notSuccess":false}   // 无 obj 字段
```

**列表项字段**（自前端 bundle 渲染代码 `_pg_js_660_508e93da_js` 反推，字段名为真实接口字段）：

| 字段 | 含义 |
| --- | --- |
| `pickUpCode` | **取件码**（存在才渲染「取件码：xxx」） |
| `waitingPickType` | `"1"` → 前端显示「请前往丰巢柜扫码取件」（此时可能无 pickUpCode） |
| `storeName` | 网点/驿站名 |
| `originateContacts` / `originateCityName` | 寄件人 / 寄件城市 |
| `destinationContacts` / `destinationCityName` | 收件人 / 收件城市 |

数据流（bundle 实证）：`queryStudentWaittingWaybill() → {success, obj} → obj || []` 进列表组件；列表空则渲染空态「近期暂无待取快递」。

**实测记录**：

- 浏览器内（校园专享页登录态）：请求复现 `st=200`，`{"success":true,...}`（当日 0 待取件，空态）；同页面 `receiveRecords`（领取记录）响应 `obj:[{cycle:"202609"},...]` 亦验证通过；
- Node 无 cookie 直连：`{"errorCode":"100111","errorMessage":"用户信息失效，请退出重新进入"}`（HTTP 200）→ 证明端点存在、签名被服务端接受，鉴权完全依赖 MIMP Cookie；
- API 定义位于前端 chunk 2093（`~memberManage~studentService~` service 组），同组还有 `createIdentityCode`/`queryStudentIdentifyInfoNew`/`monthPacketRecords`/`receiveRecords` 等，可按 4.4 模式直接复用。

***

## 7. YYB-GO 集成契约

### 7.1 角色与边界

- YYB-GO 部署在负责人服务器上，**只负责登录面**：接收 wx.login code（或由服务侧模拟微信登录环境获取 code）、调 `/e-mini/mini/login` 签发 token；
- **数据面仍由 App 直连** `ant.yshoufa.com`（凭据 + 签名本地算，签名算法见 9.1，无设备指纹依赖）；直连被风控卡死时才启用 `proxy` 兜底。

### 7.2 接口契约 v0（提案，联调前与负责人确认实际部署版本）

```
POST {BASE}/api/v1/sf/login
  body: { "wxCode": "<wx.login code>" }        // code 一次性，需即时转发
  resp: { "code": 0, "message": "", "data": {
            "token": "<YJY-Auth JWT>",
            "extra": { "mobile": "...", "userId": "..." },
            "expiresAt": 1790503507000         // JWT exp，毫秒
         } }

POST {BASE}/api/v1/sf/refresh                 // 同 login，刷新已失效凭据

POST {BASE}/api/v1/sf/proxy                   // 兜底代理模式
  body: { "api": "...", "method": "POST", "headers": {...}, "body": "..." }
  resp: 平台响应透传

GET  {BASE}/api/v1/health                     // 健康检查
```

### 7.3 App 侧实现要求

- 复用 core:network 的 `YybGoClient`；
- 超时（连接 5s / 读 30s）、失败重试 1 次、`code != 0` → `PlatformError` 归一；
- 凭据拿到后**立即加密落盘**，`expiresAt`（= JWT exp）持久化，提前 2h 视为临期（token 仅 \~24h 有效，不宜按 24h 提前量）；
- BASE URL 支持在「我的-设置」中修改。

***

## 8. 抓包/逆向任务清单

| # | 任务                                                                 | 状态   | 产出归档                                                          |
| - | ------------------------------------------------------------------ | ---- | ------------------------------------------------------------- |
| 1 | 解包顺丰速运小程序，梳理页面与接口清单                                                | ✅    | `D:\身份码聚合app\sf\wx9aa8425de154698d_unpacked\`（本地留存）；接口清单已进本文档 |
| 2 | 登录链路逆向                                                             | ✅    | 2.2 节已回填                                                      |
| 3 | 身份码接口 + 生成规律研究                                                     | ✅    | 4.2/4.3 节已回填                                                  |
| 4 | 快递列表接口验证（含翻页/state 筛选）                                             | ✅ 实测 | 5.1 节已回填                                                      |
| 5 | 运单详情/取件码接口验证                                                       | ✅ 实测 | 5.2 节已回填                                                      |
| 9 | 校园专享 H5 待取快递列表 + 取件码字段（MIMP queryStudentWaittingWaybill）           | ✅ 实测 | 5.3 节已回填（pickUpCode 直出；签名二样本对拍；浏览器登录态 st=200）                 |
| 6 | YYB-GO 登录联调                                                        | ⬜ 待做 | 联调记录进本文档 2.2                                                  |
| 7 | 官方顺丰速运小程序：登录页分包解包（微信内进一次短信登录页，触发 `pages/common/login` 分包下载后重新导出解包） | ⬜ 待做 | 确认短信登录提交接口与参数名，回填 2.4.1                                       |
| 8 | 官方顺丰速运小程序：短信登录全流程抓包                                                | ⬜ 待做 | 见下方 8.1 抓包清单，回填 2.4.1/2.4.2                                   |

### 8.1 官方顺丰速运小程序抓包清单（去 YYB 路线，PC 微信 + Charles/Fiddler，小号操作）

按顺序在官方「顺丰速运」小程序（`wxd4185d00bf7e08ac`）中操作，每个请求存 `URL + 完整 Headers + Body + 响应 JSON`（脱敏）：

| # | 操作              | 重点看什么                                                                                                                              |
| - | --------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| 1 | 冷启动小程序（首页）      | `GET /wxaccess/weixin/appOnLogin?code=..&publicId=..` 的完整响应（sessionId/openid 字段名与结构）、签名三头 `appId/sign/nonceStr` 实际值（对拍 2.4.1 签名算法） |
| 2 | 打开登录页、点「获取验证码」  | ① 发码请求真实 URL/method（`GET /wxopen/login/code?mobile=..`？）② 是否带 `suuid` 头 ③ 是否触发极验（`gt-*` 头出现条件与取值来源）④ 发码响应 errorCode                |
| 3 | 输入短信码点登录        | **最关键**：提交接口真实 URL/method/参数名（验证 bindMember 推测）+ 响应体（是否返回新 sessionId、memId/mobile）                                                 |
| 4 | 登录成功后打开「我的」     | 登录态接口（如 `/wxopen/wxMemIsBind`）请求头里 `suuid` 的值——是否就是 #1/#3 返回的 sessionId                                                            |
| 5 | 打开身份码页          | **身份码接口**：URL、用哪套签名（appId 是否 `authenticationRnApp`）、必需 header（suuid？）、响应结构；对照驿收发 credentialPerson 的 `data` 格式                      |
| 6 | 等待 3 分钟看身份码自动刷新 | 刷新请求频率与 #5 是否一致；确认该体系身份码是否也是「静态段+时间」伪动态结构                                                                                          |
| 7 | 登录后放置 30 分钟再操作  | session 过期后的表现：哪个错误码/HTTP 状态 → 作为 checkAlive 判定依据；过期后小程序如何自动重登（是否静默 wx.login 重登，短信登录态是否还在）                                         |
| 8 | （可选）断网重进        | 验证 sessionId 本地缓存（`STG_sessionId`）与 1700s 过期逻辑的实际行为                                                                                |

**抓包注意**：

- 域名过滤只看 `ucmp.sf-express.com`（业务）+ `servicewechat.com`（分包下载，#7 顺带把 login 分包 wxapkg 存下来）；
- \#2/#3 之间间隔短（验证码 60s 级别），建议 Fiddler `bpu` 断点方式先截发码请求确认头，再放行；
- 若发码触发极验（#2 出现 gt 头），需额外抓 `static.geetest.com` 的 captcha 加载请求（captchaId 已知，重点记录 validate 流程可否人机分离复用）。

**归档要求**：每个接口存 `URL + Headers + Body + 响应 JSON`；**脱敏**——替换真实 token、手机号、运单号、签名值（保留格式与长度）。

***

## 9. 签名与风控注意（✅ 签名算法已破解并验证）

### 9.1 签名算法（实测与抓包 100% 吻合）

请求头需携带：

```
X-Ca-Sign-Type: MD5
X-Ca-Nonce: <UUID v4>
X-Ca-Timestamp: <毫秒时间戳>
X-Ca-Signature: <MD5 签名>
YJY-Auth: <JWT，登录接口除外>
```

签名串构造（`utils/sign.js`）：

```
1. 参与字段: METHOD, X-Ca-Sign-Type, X-Ca-Nonce, X-Ca-Timestamp, YJY-Auth
   （POST 再加 BODY = MD5(JSON.stringify(body)).toLowerCase()；GET 的 query params 也平铺进参与字段）
2. 过滤 null/空串/对象值，按 key 字典序排序，拼 k=v
3. 末尾追加 key=<secretKey>（生产: 见 yshoufa-client.js 内 SECRET_KEY，源自解包 utils/env.js）
4. 用 & 连接后取 MD5 小写
```

参考实现：`D:\身份码聚合app\yshoufa-client.js`（Node）。

### 9.2 已实测的风控特征

- **网关不做 nonce 防重放**：同 timestamp+nonce+signature 原样重放成功 → 脚本可固定签名头复用；
- **缺签名头**返回 `e-gateway_100_P_205`「APP版本过低，请升级版本」（网关层拦截）；
- 时间戳显著偏离会被拒（http.js 错误码 400 提示「检查系统时间」）；
- `Access-Control-Allow-Origin: *`，无 CORS 限制，无 Referer/UA 校验（已实测自定义 UA 可通过）。

### 9.3 工程注意

1. **签名器独立成类**（`SfSigner`）：9.1 全部封装 + 单测（用已验证抓包样本做黄金用例）；接口变更时只改签名器；
2. **频率**：业务请求最小间隔 ≥3s + 随机抖动；静态段缓存后身份码本地生成，请求频率极低；
3. **小号专用**：所有逆向调试用微信小号 + 专用设备，**严禁主号**；
4. **禁止**：日志打印 token/签名；解包源码、真实凭据、未脱敏抓包提交进仓库；`secretKey` 不硬编码进 App 仓库（由 YYB-GO 或构建期注入）；
5. 本模块仅供个人学习自用，遵守总规划第 9 节合规声明。

***

## 10. 建议开发步骤

1. 建模块 `:platform:sf`，`SfAdapter` 骨架（`loginMode = REMOTE_SERVICE`），Hilt 注册跑通；
2. `SfSigner` 签名器（黄金用例：已验证的抓包样本）；
3. `remoteLogin()` 联调（YybGoClient，契约 7.2）→ 凭据落盘（验收 1、2 项）；
4. `checkAlive`（loginUserInfo 探测）；
5. `fetchIdentityCode`：credentialPerson 拿静态段 → 本地公式生成 + 3 分钟周期校准（4.3）；
6. `fetchExpressList` → `fetchPickupCode`（候选接口见第 5 节，先抓包补字段映射）；
7. 解析器单测 + 异常全路径归一 `PlatformError`；
8. 跑验收清单（第 11 节），提 PR。

## 11. 验收清单（顺丰专项）

- [ ] YYB-GO 登录链路联调通过（真实 code→token），凭据加密落盘
- [ ] 杀进程重开，checkAlive 通过
- [ ] 快递列表字段与小程序一致，翻页正常
- [ ] 取件码查询正常
- [ ] 身份码条形码展示正常；本地生成值与服务端返回值一致（黄金用例）
- [ ] token 过期 → `NeedRelogin` → 自动走 YYB-GO refresh 或 UI 标红
- [ ] YYB-GO 停机时：有效凭据正常跑；失效凭据 UI 提示，不 crash
- [ ] 签名器/解析器单测通过；mock 数据齐全且脱敏
- [ ] PR 带验收截图

***

*文档版本：v2.2 ｜ 2026-09-29 ｜ 新增 2.4 官方顺丰速运小程序短信登录路线（去 YYB，逆向中）与 8.1 抓包清单；2.2 驿收发路线保留为主路线 ｜ 上一版 v2.1（2026-09-26 核心链路逆向完成并回填）*
