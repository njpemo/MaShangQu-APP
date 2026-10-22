# 如意邻里（圆通速递）小程序逆向分析

> 目标小程序：如意邻里 wx814446d567063840（uni-app 构建，圆通速递社区服务：驿站取件/查件/寄件）
> 分析对象：wxapkg 解包产物（仅主包），核心为 `app-service.js`（7,755 行 / 1,570,769 字符 / 710 个 webpack 模块）
> 提取产物：`ryll/_extract/`（modules_index.txt、module_*.js、m2_*.js、page_*.js、kw*.txt）

---

## 1. 环境配置与网关（模块 c398）

生产环境 `ENV=prod`（v2.1.18）：

| 配置项 | 值 |
|---|---|
| baseApiUrl | `https://mec.yto.net.cn`（所有 `/api/**` 主网关） |
| aiApiUrl | `https://mec-ai.yto.net.cn` |
| stationUrl | `https://yizhan.mamayz.com:49197/opYZAppPackage/`（驿站/驿站柜，`/station-api/` 前缀路由替换至此） |
| ruyiApiUrl | `https://community.ruyishop.cn/api/`（如意社区） |
| requestWechatPay | `https://wxpay.yto.net.cn` |
| staticUrl | `https://ec-rs-cdn.yto56.com.cn/` |
| goodsPicUrl | `https://scs.yto56.com.cn:48865/yzbasedata/stationInfo/getImage?id=` |

UAT：`https://mecuat.yto56test.com:3443`；preProd：`https://mecpre.yto.net.cn`。

### 请求封装 URL 路由规则（模块 4ffb/5e3）
- `/wepay/` → 代理微信支付
- `/ai/` → aiApiUrl
- `/station-api/` 前缀 → 替换为 stationUrl（如 `/station-api/rypackage/waitAllOrderList` → `https://yizhan.mamayz.com:49197/opYZAppPackage/rypackage/waitAllOrderList`）
- `/mallapi/` → preProd mall 网关
- 其余 → baseApiUrl + 路径

## 2. 请求封装与鉴权

请求封装（uni.request 包装）默认项：`{showLoading:!1, resHasHeader:!1, reqNeedToken:!1, reqNeedLogin:!1}`。

请求头组装顺序：
1. `source` = channelName() = **`TB_WX_APPLET`**（模块 965e；旧版 6698 为 `WX_APPLET`，未使用）
2. `jwt-token` = Vuex state.token（业务接口必带）
3. `anonymouscode`（ttonymousCode，仅 thirdlogin 用）
4. 登录请求专用：`authorization`(soldierToken) + `sign`(soldierSign)

响应处理：`200 + resNeedHeader` → resolve 整个响应（登录取 `res.header["jwt-token"]` 用）；否则 resolve data。
**401 → 自动调 thirdLogin 重登**（重试上限 3 次，newThirdLogin 为 2 次）。
错误 toast 豁免名单（静默失败接口）：addressCode/detail、senderRealNameCheck、selfPrint、smartEntering、freight、waybillinfo、findUsername、saveUrgeToC5、customerEmpInfo、batchSaveOrder&&status1002、`/order$`、advertising/getList、receiveReward、adAssociate、realnamechat、getSendConfig、homePackageCount。

> 注：81cc 引用的请求封装模块 ID 为 `5000`，该模块不在解包产物内（应属未包含的运行时 chunk）；但 4ffb 中的同形封装已完整还原，行为一致。

## 3. 登录链路

### 3.1 微信一键登录（thirdlogin，自动登录/兜底重登）

```
wx.login() → code
GET /api/thirdlogin/{source}/{code截取&前}?a=123
  headers: { source: "TB_WX_APPLET", anonymouscode }
  reqNeedToken:!1, resNeedHeader:!0
→ 成功: res.data（用户信息，含 openId）+ res.header["jwt-token"]
→ commit thirdlLogin / setOpenId / setToken；EventBus.emit("getTokenSuccess")
→ 失败重试上限 3 次
```

登录成功后自动补挂：`bindExpressman`（POST /api/salesman/thirdpartysalesman）、`bindAddress`（POST /api/user/thirdpartyaddress）。

### 3.2 手机号验证码登录（subMePages 分包已补齐，全链路已从代码还原）

三个页面：`login/login`（微信一键登录壳，goWechatLogin → keyLoginBtn → thirdlogin）→ `login/bindMobile`（手机号+图形码）→ `login/verifCode`（短信码提交）。

**bindMobile 页（模块 ce89）逻辑：**
1. `getVcode()` → `GET /api/user/vcode` → 返回 `{base64Img, randValue}`（图形码 4 位，base64 图片直接展示）
2. 输手机号（11 位，isMobile 校验）+ 4 位图形码
3. `loginSendSms({randValue, code:图形码, username:手机号})` → `POST /api/loginsendsms?randValue=&code=&username=` → 发短信（失败自动刷新图形码）

**verifCode 页（模块 6311）逻辑：**
```js
finish(smsCode) {
  smsLogin({ username: 手机号, flag: 2, smsCode: 6位短信码 })
    .then(res => { if (res.token) { setToken(res.token); thirdlLogin(res.body);
      // 登录成功后补挂 bindAddress + bindExpressman
    }})
}
// 即 POST /api/user/login?username=<手机号>&flag=2&smsCode=<6位码>（qsStringify 拼 query）
// 响应 { token, body } —— token 即 jwt-token，body 即用户信息（与 thirdlogin 同构）
```

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/user/vcode` | GET | 图形验证码，返回 `{base64Img, randValue}` |
| `/api/loginsendsms?randValue=&code=&username=` | POST | 发送短信验证码（code=4位图形码） |
| `/api/user/login?username=&flag=2&smsCode=` | POST | **短信码登录**（smsLogin），响应 `{token, body}` |

注：81cc 里的 `getLoginsms`（POST /api/loginsms）在登录流程中**未被使用**（分包内零调用点），实际登录走 smsLogin。`flag:2` 语义为短信验证码登录（搜索页百度链路用 flag:1）。

其他用户接口：`/api/user/getuserinfo`、`/api/refreshtoken`（token 续期）、`/api/user/releasebind`（解绑）、`/api/wxPhone`（微信手机号快捷授权，reqNeedToken）、`/api/user/realname`。

另有百度系登录 `smsLogin({...,"BAIDUXCX"})`（搜索页 loginByBaiDu 使用，返回 `{token, body}`）。

> 局限说明：登录页分包代码缺失，getLoginsms 确切参数名需运行时抓包确认；但发送验证码依赖图形验证码（vcode）这一点已确定。

## 4. 身份码

### 4.1 获取接口（模块 055c）

```js
queryIdentityCode: GET /api/user/getPinCode   // reqNeedToken: !0
```

入口：首页 toolList「身份码」→ `/subCMmy/identity/identityCode`（custom 导航，分包页面，UI 代码缺失）。

### 4.2 静态身份码定性（✅ 2026-09-29 已实测确认：静态码）

**实测结果（短信登录 jwt-token → 连续两次 GET /api/user/getPinCode）：**

- 接口返回 `{status:0, data:"data:image/png;base64,..."}`，PNG 尺寸 919×223 —— 是 **Code128 一维条码图**（不是方形二维码），条码下方明文印着码值
- 两次调用返回的**码值完全相同**：`5Q394MCKDBENH9XX`（16 位，大写字母+数字，仅图片字节级重渲染不同）
- 解码脚本：`ryll/js/qrdecode/qr.js`（jsqr 只解二维码，一维码直接目视条码下明文即可；产物 pin1.png/pin2.png）

**结论：**
1. 如意邻里身份码 = **用户静态 PIN**（与用户账号绑定，非时间戳/随机段动态码），与顺丰 ACS 静态段同模式
2. 登录一次拿到 jwt-token → 调一次 getPinCode 提取码值 → **本地永久缓存，App 端可用 Code128 本地绘制条码**，无需每次请求
3. 渲染建议：App 端用 Code128 编码库本地生成条码图（内容即 16 位 PIN），或直接缓存服务端返回的 base64 PNG
4. 前端本地无加密/变换（服务端直接下发成品条码图，主包 yzQrcode/tki-qrcode 仅用于寄件码等其他场景）

## 5. 快递列表（驿站包裹）

### 5.1 页面逻辑（pages/package，模块 d83d）

双数据源并行（tab0=待取件 / tab1=已取件）：

```js
init: Promise.all([
  stationPackageList({flag:1}),   // 驿站在架包裹
  shelfPackageList()              // 驿站柜货架包裹
])
// 各返回 data.waitTakeOrderResponseList（待取）/ data.takeOrderResponseList（已取）
// 按驿站分组 dealWithPackage → stationList
// 角标 waitNum = ΣwaitOrderNum, gotNum = ΣtakenNum
```

首页角标：`POST /station-api/rypackage/homePackageCount`（模块 4895，reqNeedToken）。

「请人代取」：storage `yto_pickup_mode=1` → package 页进入勾选模式 → 分享卡片
`/subCMothers/station/packageShare?station={encodeURIComponent(JSON.stringify(stationList))}`。

### 5.2 驿站/驿站柜接口全景（模块 d68f，全部 reqNeedToken + showLoading:!1）

| 方法 | 接口 | 用途 |
|---|---|---|
| getStationList | POST /api/station/pickUpStationList | 驿站列表 |
| getStationExpressList | POST /api/station/stationTakeOrderList | 驿站代取订单列表 |
| subscribeStationExpress | POST /api/station/batchBookingPickUp | 批量预约代取 |
| confirmStationExpress | POST /api/station/batchConfirmPickUp | 批量确认取件 |
| autoSubscribeExpress | POST /api/station/AutoBookingPickUp | 自动预约 |
| checkSpecialStation | GET /api/station/checkStationWhitelist | 白名单 |
| searchStationInfo | POST /api/station/queryIncomeByHY | 驿站信息 |
| queryStationExpress | POST /api/order/waitOrderListByDc | 待取列表 |
| queryStationStatus | POST /api/order/yzPickUpStatusList | 取件状态 |
| queryStationPackage | POST /api/order/cluster/waitAllOrderList | 聚合待取（驿站角标 waitOrderNum） |
| stationPackageList | POST /station-api/rypackage/waitAllOrderList | **快递页主列表** |
| shelfPackageList | POST /station-api/rypackage/queryCabinetByPhone | **货架/柜包裹（按手机号）** |
| queryCabinetInfo | POST /api/selfPickupLocker/getLockerInfo | 柜机信息 |
| queryPackageList | POST /api/selfPickupLocker/getPackageList | 柜内包裹列表 |
| gotCodeDoorOpen | POST /api/selfPickupLocker/openByTakeCode | **取件码开柜** |
| cabinetDoorOpen | POST /api/selfPickupLocker/openByWaybillCode | **运单号开柜** |
| completeDoorOpen | POST /api/selfPickupLocker/openByCompletePackageInfo | 整包信息开门 |
| packageConfirm | POST /api/selfPickupLocker/pkgTakeConfirm | 取件确认 |
| queryCabinetStatus | POST /api/selfPickupLocker/queryOpenStatus | 开门状态 |
| stationOpenDoor / cabinetOpenDoor | POST /api/station/openDoor / openCabinetDoor | 驿站/柜开门 |

## 6. 查快递信息（pages/search，模块 970d）

三个 tab：收到(0)/寄出(1)/关注(2)，列表参数：

```js
{ limit:10, pageNo, orderStatusList:[], startTime/endTime(ISO8601+08:00),
  ids:[](亲属号筛选) }
// tab0: queryReceiveOrders → POST /api/order/queryRecieveOrderListByDc   (receiverPhone=手机号)
// tab1: querySendOrders    → POST /api/order/orderList                    (flag:0)
// tab2: searchFollowList   → POST /api/waybillFollow/searchFollowList
// 筛选条件: GET /api/order/getSearchAllCondition {type:1收/0寄}
```

运单号校验（模块 740b，正则可由服务端 `wayBillRegExp` storage 覆盖）：
`/^[1,2,5,6,7,8][0-9]{11}$|^YT[0-9]{13}$|^YTD[0-9]{12}$|^YTG[0-9]{12}$|^G8[0-9]{16}$|^DD[0-9]{16}$|^[6-9][0-9]{17}$|^DD[0-9]{10}$|^DB[0-9]{10}$|^M[0-9]{14}$|^JY[0-9]{10}$|^[C,D,M,G,V,L,B][0-9]{11}$/`

查单流程（hisConfirm）：`waybillIsFollow` → `queryPersonalOrder`（POST /api/order/personOrderByMailNoOrOrderNo，校验是否本人单）→ 非本人单需 **手机后四位验证** `queryLastfourphone`（POST /api/order/mobileCheck，body `{lastFourMobile, waybillNo}`，返回 true/false）→ 跳详情 `/subCMothers/wayBill/newWaybill?mailNo=...&lastfourphone=...`。

**mobileCheck 全链实测（2026-10-22，样本 YT7651323338983）**：
- 必须 JSON body（form 表单 → `-57029`；`mailNo` 键名 → `10002`），响应体为**裸布尔** `true/false`（无信封壳）。
- 防遍历 A/B：`0000→false`、`7330→false`、`5220(正确)→true`；**已签收单恒 true（不设防）**；假单号 false；无错误码、无频控迹象。
- **验证态绑定 jwt-token 会话**：mobileCheck 通过后，同一 token 再查 `GET /api/order/orderDetailByWayBillNo`，响应多出 `recieveOrderDetailResponse.value.stationCode = 取件码`（如 `"439-1-495"`，`ocOrderDetailResponse.stationCode` 同值）+ `storageTakingCode`（驿站取件凭证号）。不带 token 永远不出该段。
- 小程序侧佐证：page_search.js `toHisSearch(searchInput, lastFour)` 仅把尾号拼进详情页 URL，验证接口不返回码，出码全靠详情接口 + 会话态。

快递详情/轨迹接口（模块 0ea1）：

| 接口 | 用途 |
|---|---|
| GET /api/order/orderDetail / queryOrderDetail / orderDetailForTb | 订单详情 |
| GET /api/order/orderDetailByWayBillNo / ByOrderNo / ByMailNo / ByOneKey | 按单号查详情 |
| GET /api/waybillinfo | 运单信息（豁免 toast） |
| POST /api/logistics/trackMapNew / trackVisual / geoCoding | 轨迹地图/可视化 |
| GET /api/order/arriveTime / geteEpectDeliveryTimeByWayBillNo | 预计送达 |
| POST /api/signPic/queryStatus / imageOrVoiceQuery / query | 签收图/语音 |
| GET /api/order/checkWaybill/{no} | 扫码校验可用性 |
| GET /api/urge/orderUrge | 催件 |
| POST /api/order/mobileCheck | 手机后四验证 |
| GET /api/order/getSearchAllCondition | 筛选条件 |
| POST /api/order/searchMobileByVirtualId(AndMailNo) | 虚拟号还原真实号 |

寄件二维码（showQRpopup）：`qrText = "SENDORDER://channelCode=..&stationCode=..&orderNo=.."`，本地 yzQrcode 渲染 —— 同样是**内容即码、本地绘制**模式。

## 7. 接口—页面—模块映射

| 页面 | 所在包 | 主模块 | 依赖 API 模块 |
|---|---|---|---|
| pages/home | 主包 ✅ | 093f | 4895(homePackageCount), 965e |
| pages/package | 主包 ✅ | d83d | d68f(station/locker API) |
| pages/search | 主包 ✅ | 970d | 0ea1(订单), 055c, 81cc, 4e0c(打印) |
| pages/me | 主包 ✅ | — | 81cc |
| pages/create | 主包 ✅ | — | 055c(智能填单/地址) |
| subMePages/login | 分包 ❌ | — | 81cc(sms 登录三件套) |
| subCMmy/identity/identityCode | 分包 ❌ | — | 055c(getPinCode) |
| subCMothers/wayBill | 分包 ❌ | — | 0ea1 |

## 8. 复现要点（App 接入建议）

1. **登录态**：短信登录已从分包代码完整还原（§3.2）：`GET /api/user/vcode` 拿图形码（{base64Img, randValue}）→ 用户输图形码+发码 → 用户输短信码 → `POST /api/user/login?username=&flag=2&smsCode=` → 响应 `{token, body}`，token 即 jwt-token。微信 one-click thirdlogin 仅小程序内可用（需 wx.login code）。
2. **必需头**：`jwt-token` + `source: TB_WX_APPLET`；station-api 走 `https://yizhan.mamayz.com:49197/opYZAppPackage/`。
3. **快递列表**：`POST /station-api/rypackage/waitAllOrderList {flag:1}` + `POST /station-api/rypackage/queryCabinetByPhone {}`，取 `data.waitTakeOrderResponseList / takeOrderResponseList`（防御式解析）。
4. **身份码**：`GET /api/user/getPinCode`（reqNeedToken）—— **已实测为静态码**（`5Q394MCKDBENH9XX`，16 位，Code128 条码图），登录后调一次提取码值本地永久缓存，App 端 Code128 本地绘制（见 §4.2）。
5. **查件**：`POST /api/order/queryRecieveOrderListByDc`（收件）与 `POST /api/order/orderList {flag:0}`（寄件）；详情 `GET /api/order/orderDetailByWayBillNo`；非本人单先过 `POST /api/order/mobileCheck {lastFourMobile, waybillNo}`。
6. **取件码开门**：`POST /api/selfPickupLocker/openByTakeCode` / `openByWaybillCode` → 轮询 `queryOpenStatus` → `pkgTakeConfirm`。
7. **限制**：登录页与身份码页 UI 在分包不在解包产物；模块 5000（81cc 实际请求封装）缺失，已用同形 4ffb 还原；对拍时如字段不符以抓包为准。

---
*生成时间：2026-09-29。分析产物见 `ryll/_extract/`。*
