# 京东模块开发文档（微信小程序 + YYB-GO 路线）

> 对应总规划：[../../../README.md](../../../README.md) 第 4 节「核心契约」、第 7 节「平台模块开发规范」。阅读前先读 4.1/4.3/4.4。
>
> 任务模块：`:platform:jd` ｜ 实现类：`JdAdapter` ｜ 分支：`platform/jd`
> **本平台 `loginMode = REMOTE_SERVICE`**：不做 WebView 登录，登录链路由自部署 YYB-GO 完成（见第 7 节）。

---

## 0. 任务目标

| 能力 | 对应契约方法 | 路线 |
|---|---|---|
| 登录态获取 | `remoteLogin()` | YYB-GO 复现京东快递小程序登录链路，**需逆向** |
| 登录态校验 | `checkAlive()` | 复用业务接口探测，方案明确 |
| 身份码 | `fetchIdentityCode()` | 小程序接口，**需逆向**；含静态码研究专项 |
| 快递列表 | `fetchExpressList()` | 小程序接口，**需逆向** |
| 取件码 | `fetchPickupCode()` | 小程序接口，**需逆向** |

**开发策略**：先完成第 3 节逆向工作流（解包+抓包）摸清全部链路 → YYB-GO 登录联调 → checkAlive → 业务接口逐个接入 → 静态身份码研究。

---

## 1. 背景知识（先读）

1. **取件场景**：京东快递派件到京东驿站/快递点后，取件人在京东 App「我的快递」或「京东快递」微信小程序里查看取件码/出示身份码。我们走**微信小程序**路线。
2. **京东移动网关**：京东系 H5/小程序数据接口大概率走统一网关 `api.m.jd.com`（`functionId + body` 参数体系）。小程序版的具体 functionId、鉴权 token 形态**以抓包/解包为准，本文档不臆测**。
3. **登录态**：登录态主体为 Cookie `pt_key` + `pt_pin`。**实测 pt_key 有效期约 1 年**（Set-Cookie `EXPIRES` 一年期，capture-once 模式可行）；`pt_pin` 即京东账号 pin（如 `jd_xxx`）。业务接口只需 `pt_key + pin` 两个值。
4. **官方开放平台不可用**：京东物流开放平台（open.jdl.com）API 面向签约商家（B2B），个人取件场景无对应接口，只能走小程序逆向。仅作背景，不展开。

---

## 2. 登录方案（REMOTE_SERVICE）

### 2.1 契约实现方式

```kotlin
@Singleton
class JdAdapter @Inject constructor(...) : PlatformAdapter {
    override val platform = Platform.JD
    override val loginMode = LoginMode.REMOTE_SERVICE
    override fun loginUrl(): String = ""          // 空实现
    override fun detectLoginSuccess(...) = null   // 空实现
    override suspend fun remoteLogin(): Credential? = yybGoClient.login(Platform.JD)
    ...
}
```

UI（「我的」页）对京东显示「通过登录服务登录」按钮 → 调 `remoteLogin()` → 凭据加密落盘。

### 2.2 登录链路（逆向已完成，Node 直连实测打通）

**核心结论**：身份码等业务接口只需 `pt_key + pin`；`pt_key` 由小程序登录态产出、有效期约 1 年。登录网关 `https://wxapplogin.m.jd.com`（appid=599 京东快递 / wxappid=wx73247c7819d61796 / 插件 pappid=wxefe655223916819e / client_ver=2.0.2）。

#### 2.2.1 登录态转移链路（YYB-GO 主用，已实测）

用已持有的 `pt_key` 为任意 H5 域铸发登录 Cookie：

```
GET /plogin/cgi-bin/app/wxapp_gentoken
    ?ts=<Unix秒>&sign=<sign>&wxappid=wx73247c7819d61796&pappid=wxefe655223916819e&appid=599&client_ver=2.0.2
    Cookie: guid=<guid>; pt_pin=<pin>; pt_key=<pt_key>; pt_token=
    → {"err_code":0,"tokenkey":"...","url":"https://wxapplogin.m.jd.com/cgi-bin/app/wxapp_appjmp"}

GET /cgi-bin/app/wxapp_appjmp?to=<目标H5 URL编码>&tokenkey=<tokenkey>
    → 302，Set-Cookie: pt_key / pt_pin / pwdt_id（DOMAIN=.jd.com，HTTPONLY，有效期约1年）
```

gentoken 签名（已与抓包对拍验证）：

```
sign = md5("appid=599&pt_key=<pt_key>&ts=<ts>" + "dzHdg!ax0g927gYr3zf&dSrvm@t4a+8F")
```

#### 2.2.2 全新登录链路（小程序原生，受限于微信环境）

```
wx.login() code + wx.getUserInfo() encryptedData/iv
→ POST /cgi-bin/v2/login (cmd=34, sub_cmd=5)
  data: code, user_data=encryptedData, user_iv=iv, eid_token, returnurl,
        wxappid, pappid, appid, client_ver, ts, sign
  sign = md5(["appid","wxappid","client_ver","ts","cmd","sub_cmd","gsalt"].map(v).join(""))
  → 响应含 guid / salt / wx_token / rsa_modulus / wx_bind / pt_key
  → **还含验证码开关与预发 jcap_sid**（2026-09-28 逆向确认）：login_need_captcha / phonelogin_need_captcha（1=需滑块）、
    login_jcap_sid / phonelogin_jcap_sid（预发会话）、captcha_ver（1=新版 H5 滑块）
```

- `wx_bind===1` 且无 pt_key → `POST /cgi-bin/v2/confirmlogin`（cmd34 sub6，data wx_token+agreement，sign 用 salt）补全登录；
- **v2/login 空 code 也能过**（2026-09-28 实测，`jd/js/test_login_nocode.js`）：code 传空串 + 其余表单不变 → err_code=0 且完整下发 `phonelogin_jcap_sid / login_jcap_sid / salt / wx_token / guid / wx_bind:0`——短信登录链路完全不依赖微信 code / YYB 服务，App 纯直连可跑；
- `wx_bind:0`（微信未绑定京东 pin）→ 打开 Jcap 插件登录页（协议见 §2.2.6）。**短信登录链路已于 2026-09-28 Node 直连全链路实测打通**（脚本 `jd/js/test_sms_prepare/send/verify.js`）：
  1. `v2/login`（cmd34 sub5，**空 code**）→ 取 `phonelogin_jcap_sid`、`salt`、`wx_token`、`guid`；
  2. Jcap fp → 滑块/点选 H5 页（任意浏览器可开）人工完成 → hook `wx.miniProgram.postMessage` 截获 `vt`；
  3. `POST /cgi-bin/v2/sendlogincode`（cmd34 sub10）：data `{jcap_sid, verify_token:vt, phone, country_code:"86", wx_token, wxappid, pappid, appid, client_ver, ts, sign}`，sign keyArr `["country_code","phone","cmd","sub_cmd","salt"]`（cmd=34, sub_cmd=10），**无需 h5st**，返回 `{err_code:0, expire_time:60}`；
  4. 用户收码 → `POST /cgi-bin/v2/dophonelogin`（cmd34 sub11）：data `{auth_code, phone, country_code:"86", agreement:"", wx_token, returnurl, wxappid, pappid, appid, client_ver, ts, sign}`，sign keyArr `["country_code","phone","cmd","sub_cmd","salt"]`（cmd=34, sub_cmd=11），**实测不带 h5st（siginAppId 84a79）也直接成功**——此前"h5st 必须"的推断不成立；
  5. 成功响应含 `pt_key` + `pt_pin`（另有 expire_time/refresh_time Unix 秒）→ 直接可调业务接口（已验证 generateIdentityCode 出码 JDME…，手机号脱敏匹配）；
- **264 排查要点（实测踩坑）**：sendlogincode/dophonelogin 请求 Cookie 必须带 `guid=<v2/login 响应的 guid>`（小程序端 v2/login 后 guid 落 storage，后续请求自动携带）；`Cookie: guid=; ...` 空 guid 直接报 `err_code 264 参数异常`，与参数内容无关；
- 密码登录 `dologin`（cmd34 sub8，RSA key=3，modulus 来自登录响应，无需 h5st）为备用链路，同样需要 vt；两条链路在 need_captcha=1 时都要求 Jcap 滑块产出的 `verify_token`（=滑块成功的 `vt`）；
- `jcap_sid` 两条获取途径：① v2/login 响应直接预发（login_jcap_sid/phonelogin_jcap_sid）；② 失效后（err_code 16808 + s_code 12901）用 `getjcapsid`（cmd34 sub7，data `{jcap_type}`，**短信=6、密码=5**，sign keyArr ["cmd","sub_cmd","salt"]，addWXToken）重新换；
- `POST /cgi-bin/jxpp/silentauthlogin`（cmd52 sub1，code+token+salt）：token 来自 isvObfuscator，而 isvObfuscator 需已登录 cookie——对首次登录无用，实测不可作为兜底；
- **受限点**：`encryptedData/iv` 只能由微信客户端在真实小程序环境生成，纯服务端无法全新登录。

#### 2.2.6 Jcap 滑块验证码协议（2026-09-28 逆向自 appservice.app.js `Jcap/*` 模块，未实测）

Jcap = 京东统一验证码插件（pappid wxefe655223916819e），服务端代码打包在 `appservice.app.js` L1493-1514（已抽出 `jd/_jcap_*.js`）。requestHost 默认 `https://wxapplogin.m.jd.com`。

**① 指纹上报 fp**（Jcap._startCheckFlow，POST、`content-type: application/x-www-form-urlencoded`）：

```
POST {requestHost}/jcap/cgi-bin/wxapp/fp
  client=wxapp & si=<jcap_sid> & version=2 & lang=1
  & ct = ENC( rand(ts%19) + pad(len(sid),4) + sid + devInfoJSON + ts )
  （ts=Date.parse=毫秒；pad=前导零补位到指定位宽；devInfoJSON 见 util.js 设备对象：
    account/ccode/pr/scr/pt/lan/cpu/dnm/dbn/dmd/anm/avs/os/osv/capfp/mem/sdv）
→ {code, st, fp, tp}
   code=0 & tp==9  → 无感通道，直接走 check（需触摸轨迹，纯服务端难伪造）
   code=0 & tp!=9  → 打开滑块 Webview
   16802/16803/16808 = 失败/风控（16808+s_code12901 → 重新 getjcapsid）
```

**② 滑块 Webview**（captcha_ver==1 新版 = jcapWrapper.createJcap；老版 myJcap 插件组件同源）：

```
url = {requestHost}/jcap/jcap/html/wx-captcha-v1.html
        ?baseUrl=...&si=<sid>&lang=1&client=wxapp&st=<fp响应的st>&fp=<fp>&appid=<wxappid>
用户滑动成功 → postMessage {type:"captcha_result", status:"success", data:{vt}}
vt 即登录 API 的 verify_token；失败/取消 → {status:"failure"|"cancel"}
```

**③ 无感 check**（tp==9 时自动走，POST /jcap/cgi-bin/wxapp/check）：

```
  client=wxapp & si=<sid> & version=2 & lang=1
  & tk = ENC( pad(len(sid),4)+sid+pad(len(st),4)+st+pad(0,6)+""+touchJSON+rand(ts%41) )
  & ct = ENC( rand(ts%19) + pad(len(sid),4) + sid + devInfoJSON + ts )
  （touchJSON = {cx,cy,sx,sy,it:initTime,list:actionList}，取自本地 jcap_touch_message 存储）
→ code=0 且无 tp → onSuccess(响应整体)，响应含 vt（=verify_token）；tp 循环
```

**④ ENC 算法**（Jcap/util/rsa.js，名为 rsa 实为 XXTEA+base64url）：

```
ENC(payload) = b64url( XXTEA_encrypt(utf8(payload), key="1542964129") )
  key = base64decode("MTU0Mjk2NDEyOQ") = "1542964129"
  b64url 字母表 = "A-Za-z0-9-_"（非标准 +/）；XXTEA delta 0x9E3779B9、rounds=6+52/n、
  输出按 UTF-16 低位字节序序列化（标准 XXTEA long2str）
随机串 rand(n)：小写字母+数字表 [0-9a-z]，captchaRandom(n)
```

**⑤ App 落地方案（用户目标：滑块人工校验注入）**：

1. Kotlin 纯 HTTP 实现 v2/login → 取 jcap_sid/rsa_modulus/开关；
2. Kotlin 实现 fp 请求（XXTEA 可 30 行实现，devInfo 伪造成合理安卓设备 JSON）；
3. tp!=9 → App WebView 加载 wx-captcha-v1.html 滑块页，**用户手动滑动**；JS 注入 hook `wx.miniProgram.postMessage`/WeixinJSBridge 拦截 `captcha_result` 的 `vt`（该 H5 原生跑在微信 webview renderer 内，App WebView 是否能独立加载需实测，风险点）；
4. vt 注入 `dologin`（密码链路，免 h5st，推荐先打通）或 `sendlogincode`；短信链路最后一步 `dophonelogin` 需 h5st（`pages/login/chunk_0.appservice.js` 混淆 SDK，siginAppId 84a79，cactus.jd.com 取 token，需 jsdom/WebView 移植）；
5. 登录成功 → err_code 0 + Set-Cookie/响应 pt_key + pt_pin → 存凭据走 §2.2.1 铸发链。

**⑥ 实测结论（2026-09-28 Node 直连，脚本 `jd/js/test_jcap_fp.js`）**：

- **fp 全链路打通**：YYB getCode → v2/login（err_code 0，返回双 jcap_sid/rsa_modulus/开关）→ POST /jcap/cgi-bin/wxapp/fp（原样加载 `_jcap_rsa.js` 的 ENC，Node 直连服务端接受）→ 返回 `code:0, tp:9, st, fp`。注意 ENC 的 XXTEA key 是 `"E736B80A35290F193C2034A8021CC63B"`（`"1542964129"` 只是 delta 常量 0x9E3779B9 的混淆拆分：1111471640+1542964129）；
- **无感 check 未放行**：tp=9 后带合成触摸轨迹（格式见 JcapWrapper 逆向：事件 `[typeIdx,x,y,screenX,screenY,dt]`，typeIdx 0/1/2/3/4=start/move/longpress/end/cancel，动作 `[start,moves[],end]`，≤32 动作）POST check → 返回 `tp:2` + base64 图片挑战（服务端判定轨迹可疑），纯 Node 无感过不掉，**必须人工滑动**；
- **滑块 H5 页可脱离微信直接加载**：`GET /jcap/jcap/html/wx-captcha-v1.html?...` 任意 UA 返回 200（19.9KB HTML，无微信环境校验）——风险点解除，App WebView 直接可用；
- **hook 点确认**：页面成功后调 `navigateBack({status,data})` → `isInMiniProgram()`（仅判断 `typeof wx!=="undefined" && wx.miniProgram`）为真时 `wx.miniProgram.postMessage({data:{type:"captcha_result",status:"success",data:{vt}}})`。App WebView 在页面 JS 执行前注入假 `wx={miniProgram:{postMessage:msg=>截获msg.data, navigateBack:()=>{}}}` 即可截获 vt，无需真微信 JSBridge；
- vt 提交后注入 dologin（待实测）。

#### 2.2.3 落地模式（capture-once + 保活）

1. 用户在 PC 微信手动登录一次小程序，抓包取 `pt_key + pt_pin + guid`（或引导用户在 YYB-GO 提交）；
2. YYB-GO 持有 pt_key，按 2.2.1 铸发 H5 cookie；业务接口 App 直连；
3. 保活候选：`POST /cgi-bin/login/refresh_a4`（data: client_type=wxapp + wxappid/pappid/appid/client_ver，无签名）。实测参数补全前报 264、补全后报 213（疑似刷新间隔未到，响应含 `refresh_time` 倒计时字段），**间隔机制待后续验证**；
4. pt_key 失效兜底：UI 走 `NeedRelogin`，提示用户重新抓取（一年一次）。

#### 2.2.4 报错语义速查（wxapplogin.m.jd.com）

| err_code | 含义 |
|---|---|
| 0 | 成功 |
| 19 | 页面已超时（wx_bind:0 时误调 confirmlogin 会出现） |
| 83 | code 无效/已被消费（wx.login code 一次性，被客户端自身消费后即 83） |
| 213 | refresh_a4 疑似刷新间隔未到 |
| 264 | 参数异常（缺 wxappid/pappid/appid/client_ver 或 body 包装） |

#### 2.2.5 参考脚本（本地留存，不提交仓库）

`jd/js/test_login.js`（v2/login+confirmlogin）、`jd/js/test_silentauth.js`（silentauthlogin）、`jd/js/test_identity_minimal.js`（身份码）。

### 2.3 凭据复用策略（降低 YYB-GO 依赖）

- App 本地凭据有效期内**绝不**重复调 YYB-GO；
- 每次业务请求前先 `checkAlive()`（本地直连探测，见第 4 节），失效才调 `remoteLogin()` 刷新；
- YYB-GO 不可达时：凭据还有效 → 正常跑；凭据已失效 → UI 标「登录服务不可用」，不 crash。

---

## 3. 小程序逆向工作流（开发前置，产出决定后续一切）

### 3.1 解包拿源码

1. **PC 微信**打开「京东快递」小程序，**把所有功能页都点一遍**（分包按需下载，没点开的页面解不到包）；
2. 取包：微信 PC 端小程序包在 `WeChat Files/Applet/<appId>/` 下（`.wxapkg`，加密态）；
3. 解密 + 解包：用 [wux1an/wxapkg](https://github.com/wux1an/wxapkg)（`scan` 一键扫描解包），或传统 wxappUnpacker 流程（解密工具 + `wuWxapkg.js`）；
4. 产出源码归档到 `docs/capture/jd/wxapkg-src/`（**仅本地留存研究，不提交仓库**——见第 9 节）。

### 3.2 源码分析（全局搜索关键词）

| 搜索词 | 找什么 |
|---|---|
| `wx.login` | 登录链路入口（code 的去向） |
| `api.m.jd.com` / `functionId` / `host` | 网关与接口清单 |
| `sign` / `h5st` / `x-api-eid` / `nonce` | 签名/风控参数生成逻辑 |
| `身份码` / `取件码` / `驿站` / `barcode` / `qrcode` | 身份码与取件码相关页面和接口 |
| `token` / `session` / `.Authorization` | 凭据形态与携带方式 |

### 3.3 抓包验证

- 首选 **PC 微信运行小程序 + Charles/Fiddler**（PC 抓包最省事，社区验证可行）；
- [WMPFDebugger](https://github.com/evi0s/WMPFDebugger) 可把小程序转发到 Chrome DevTools，调试请求/签名函数很方便；
- ⚠️ **一律用微信小号 + 专用设备**，主号有封号风险；不要在虚拟机里登微信做调试。

### 3.4 逆向产出（必须回填本文档后才开始写业务代码）

- [ ] 登录链路图（每个请求的 URL/Method/Headers/Body/响应）→ 回填 2.2
- [ ] 业务接口清单：身份码 / 快递列表 / 取件码 → 回填第 4、5 节
- [ ] 签名算法说明与参考实现 → 回填第 9 节
- [ ] token 形态与有效期 → 回填 1.3
- [ ] 抓包样本（脱敏）→ 归档 `docs/capture/jd/`

---

## 4. checkAlive 与身份码

### 4.1 checkAlive

不单独找校验接口，**复用业务接口探测**：调快递列表第一页接口（第 5 节），按响应错误码判断：

- 正常返回 → true；登录态失效类错误码 → false（映射 `NeedRelogin`，UI 触发 `remoteLogin()`）；
- 结果缓存 5 分钟（`PlatformLoginState.lastCheckAt`），避免频繁探测。

### 4.2 身份码获取（fetchIdentityCode）✅ 接口已打通（Node 直连实测）

```
POST https://lop-proxy.jd.com/generateIdentityCode
Cookie: pt_key=<pt_key>; pin=<pt_pin>;
Content-Type: application/json
ClientInfo: {"client":"m"}
AppParams: {"appid":"2410","ticket_type":"m"}
LOP-DN: convenient-h5.jd.com
Origin: https://convenient-h5.jd.com

body: [{"source":"ConvenientH5","jdPin":-1}]
```

- **无任何签名/anti-content**，Node 直连成功；
- 响应：`{"callState":1,"data":{"expirationTime":1200,"identityCode":"JDM51MUILOP9CXQ449O3","phone":"132****7330"},"errorCode":0,"errorDesc":"成功"}`；
- 映射：`identityCode` → 码内容（本地 ZXing 渲染 BARCODE/QR），`expirationTime: 1200` 秒 → `expireAt`；
- 码为**动态签发**（每次不同，JDM 前缀），静态码研究（4.3）按动态路线处理：按 1200s 时效刷新。

### 4.3 静态身份码研究专项（独立任务，产出报告）

**目的**：若身份码是静态/长期有效的，可免刷新、离线展示、大幅降低请求频率与风控暴露。

**方法**：

1. 同一账号**间隔 ≥24h 连续取码 ≥3 次**，diff 码内容是否变化；
2. 解包分析码的生成位置：服务端下发（接口返回码内容）还是客户端拼串生成；
3. 检查响应/源码中的时效字段（expireAt、有效期注释、倒计时逻辑）；
4. 判断码内容构成：是否嵌时间戳/nonce/签名（含则大概率动态）。

**结论三分类与落地方案**：

| 结论 | 方案 |
|---|---|
| 静态（多次取码内容不变） | 首次获取后永久缓存，离线可用，不再请求 |
| 客户端可生成（纯本地算法） | 移植生成算法进 App，离线渲染 |
| 服务端动态签发 | 按时效刷新（默认方案） |

**产出**：`docs/capture/jd/identity-code/research-static.md`（数据对比记录 + 结论），结论回填本节。

---

## 5. 快递列表 / 取件码

### 5.1 快递列表（fetchExpressList）

抓包目标：小程序查件/待取件列表接口（`api.m.jd.com` 的 functionId，待确认）。映射 `ExpressItem`：

| ExpressItem 字段 | 预期来源（以实际抓包为准） |
|---|---|
| `id` | 订单/包裹 ID（取件码查询入参） |
| `station` | 驿站/快递点名称 |
| `trackingNo` | 运单号 |
| `pickupCode` | 取件码（列表若直接带则透传） |
| `summary` | 物品/商家描述 |
| `status` | 派送中/待取件/已取出 |
| `arrivedAt` / `fetchDeadline` | 到件时间 / 最迟取件时间 |

注意分页参数（page/size 或 cursor）与筛选维度（全部/待取件/已取出）。

### 5.2 取件码（fetchPickupCode）

1. 列表直接带取件码 → 本地组装返回，不再发请求；
2. 需要详情 → 抓包包裹详情接口，按 `expressId` 查询，映射 `PickupCode`（若支持扫码取件记录 `qrContent`）。

---

## 7. YYB-GO 集成契约

### 7.1 角色与边界

- YYB-GO 部署在负责人服务器上，**只负责登录面**：复现小程序登录链路、签发/刷新凭据；
- **数据面仍由 App 直连**京东网关（凭据 + 签名本地算）；仅当直连被风控卡死时启用 `proxy` 代理模式兜底。

### 7.2 接口契约 v0（提案，联调前与负责人确认实际部署版本）

```
POST {BASE}/api/v1/jd/login
  body: { "deviceTag": "app-xxx" }            // 可选，设备标识
  resp: { "code": 0, "message": "", "data": {
            "token": "...",                    // 京东侧登录态主体
            "extra": { "...": "..." },         // pin、辅助字段
            "expiresAt": 1735689600000         // 毫秒时间戳，可空
         } }

POST {BASE}/api/v1/jd/refresh                  // 同 login，刷新已失效凭据

POST {BASE}/api/v1/jd/proxy                    // 兜底代理模式
  body: { "api": "...", "method": "POST", "headers": {...}, "body": "..." }
  resp: 平台响应透传

GET  {BASE}/api/v1/health                      // 健康检查
```

### 7.3 App 侧实现要求

- `YybGoClient`（core:network）：统一封装 BASE URL（DataStore 可配置）、超时（连接 5s / 读 30s）、失败重试 1 次、`code != 0` → `PlatformError` 归一（服务不可达 → `NetworkError`，登录失败 → `NeedRelogin` 附原因）；
- 凭据拿到后**立即加密落盘**（CredentialStore），`expiresAt` 持久化，提前 24h 视为临期；
- BASE URL 支持在「我的-设置」中修改（部署地址可能变）。

---

## 8. 抓包/逆向任务清单

| # | 任务 | 产出归档 |
|---|---|---|
| 1 | ✅ 解包京东快递小程序，梳理页面与接口清单 | `jd/wx73247c7819d61796_unpacked/`（本地留存）；Jcap 登录插件链路已还原进 2.2 |
| 2 | ✅ 登录链路抓包 + Node 复现（登录态转移链路已实测打通） | 链路进 2.2；抓包样本待脱敏归档 `docs/capture/jd/login/` |
| 3 | ✅ 身份码接口抓包 + Node 复现（generateIdentityCode 已打通） | 接口进 4.2；样本待归档 `docs/capture/jd/identity-code/` |
| 4 | 快递列表接口抓包（含翻页/筛选） | `docs/capture/jd/express-list/` |
| 5 | 取件码接口抓包 | `docs/capture/jd/pickup-code/` |
| 6 | 静态身份码研究（第 4.3 节；初判为动态码，按 4.2 结论处理） | `docs/capture/jd/identity-code/research-static.md` |
| 7 | YYB-GO 登录联调（按 2.2.1 链路实现 gentoken→appjmp） | 联调记录进本文档 2.2 |
| 8 | refresh_a4 保活间隔机制验证（2.2.3 第 3 步） | 结论回填 2.2.3 |

**归档要求**：每个接口存 `URL + Headers + Body + 响应 JSON`；**脱敏**——替换真实 token、pin、手机号、签名值（保留格式与长度）。

---

## 9. 签名与风控注意

1. **签名器独立成类**（如 `JdSigner`）：目前已知两处签名均极简——① 登录网关 `sign = md5(字段值按 ["appid","wxappid","client_ver","ts",...keyArr] 拼接)`（gsalt 固定 `sb2cwlYyaCSN1KUv5RHG3tmqxfEb8NKN`，salt 来自登录响应）；② gentoken 转移签名（2.2.1，含固定密钥串）。身份码接口**无签名**。全部封装进签名器 + 单测；接口变更时只改签名器；
2. **设备指纹**：登录链路里的设备参数（model、uuid 等）由 YYB-GO 侧固定维护，App 侧不要每次随机生成（不稳定指纹易触发风控）；
3. **频率**：业务请求最小间隔 ≥3s + 随机抖动；静态码研究确认静态后频率进一步降低；
4. **小号专用**：所有逆向调试用微信小号 + 专用设备，**严禁主号**；
5. **禁止**：日志打印 token/签名；解包源码、真实凭据、未脱敏抓包提交进仓库；
6. 本模块仅供个人学习自用，遵守总规划第 9 节合规声明。

---

## 10. 建议开发步骤

1. 建模块 `:platform:jd`，`JdAdapter` 骨架（`loginMode = REMOTE_SERVICE`，业务方法抛占位异常），Hilt 注册跑通；
2. ~~逆向工作流~~ ✅ 已完成：登录链路（2.2）与身份码（4.2）已回填；
3. 与负责人过一遍接口清单和 YYB-GO 契约 v0，定稿（重点确认 capture-once 模式下 `POST /api/v1/jd/login` 语义：改为「提交 pt_key」而非自动登录）；
4. `YybGoClient` + 凭据提交/落盘联调（验收 1、2 项）；
5. `checkAlive`（复用列表接口）；
6. `fetchIdentityCode`（4.2 已就绪）→ `fetchExpressList` → `fetchPickupCode`；
7. 静态身份码研究（第 4.3 节，初判动态码）；
8. `JdSigner` 签名器 + 解析器单测；异常全路径归一 `PlatformError`；
9. 跑验收清单（第 11 节），提 PR。

## 11. 验收清单（京东专项）

- [ ] YYB-GO 登录链路联调通过，凭据加密落盘
- [ ] 杀进程重开，checkAlive 通过（凭据有效期逻辑生效）
- [ ] 快递列表字段与小程序一致，翻页正常
- [ ] 取件码查询正常；身份码展示正常（本地渲染或 IMAGE_URL）
- [ ] 凭据失效 → `NeedRelogin` → 自动走 YYB-GO refresh 或 UI 标红
- [ ] YYB-GO 停机时：有效凭据正常跑；失效凭据 UI 提示「登录服务不可用」，不 crash
- [ ] 静态身份码研究报告产出，结论回填
- [ ] 签名器/解析器单测通过；mock 数据齐全且脱敏
- [ ] PR 带验收截图

---

*文档版本：v1.1 ｜ 2026-09-27 ｜ 回填逆向产出：登录态转移链路（2.2.1，实测打通）、全新登录链路与限制（2.2.2）、capture-once 落地模式（2.2.3）、报错语义（2.2.4）、身份码接口（4.2，实测打通）、签名说明（9.1）。契约变更同步总规划 README 第 4 节*
