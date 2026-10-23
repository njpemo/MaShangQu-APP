<div align="center">

# 码上取 · MashangQu

**一个 App，取遍所有驿站。**

拼多多 · 淘宝菜鸟 · 京东 · 顺丰 · 如意邻里 —— 身份码与快递信息一站式聚合

[![Platform](https://img.shields.io/badge/platform-Android%207.0%2B-3DDC84?logo=android&logoColor=white)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin&logoColor=white)](#)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)](#)
[![Min SDK](https://img.shields.io/badge/minSdk-26-34A853)](#)
[![Release](https://img.shields.io/github/v/release/njpemo/mashangqu?color=F57C00)](https://github.com/njpemo/mashangqu/releases)

</div>

---

<div align="center">

<p>
  <img src="docs/images/page-identity.png" width="240" />
  <img src="docs/images/page-express.png" width="240" />
  <img src="docs/images/page-mine.png" width="240" />
</p>

*身份码大屏出示 ｜ 快递列表聚合 ｜ 登录态与日志管理*

</div>

## ✨ 核心特性

| | 特性 | 说明 |
|:---:|---|---|
| 🎫 | **五平台身份码聚合** | 拼多多 / 淘宝菜鸟 / 京东 / 顺丰 / 如意邻里，大幅二维码条码一屏出示，下拉刷新 + 可配置自动刷新；顺丰身份码**本地公式生成**，零网络调用 |
| 📦 | **快递列表聚合** | 多平台站点包裹统一视图，按平台 / 站点筛选，跨平台按运单号去重合并、互补查码，本地缓存离线可查 |
| 🔢 | **取件码直达** | 单件快递取件码大字大图展示，驿站柜台一步到位 |
| 🔐 | **登录态守护** | 启动自动巡检各平台凭据有效性，失效标红、一键重登 |
| ⬆️ | **应用内更新** | 启动静默检查新版本，应用内下载 APK 直接拉起安装 |
| 📋 | **调试日志导出** | 内置实时日志面板，支持一键导出 txt 排查问题 |

## 🛡️ 隐私与架构

**无后端依赖、数据不出设备。** 登录凭据存放于本机 Keystore + EncryptedSharedPreferences 加密存储，所有请求由 App 直连平台接口，不经过任何第三方服务器。

```
┌────────────────────────── app（宿主壳）──────────────────────────┐
│   身份码页 ｜ 快递页 ｜ 我的页 ｜ 导航 ｜ 应用内更新             │
├──────────────────────────── core ────────────────────────────────┤
│   model（统一模型）  network（OkHttp）  storage（加密存储/Room） │
│   webview（通用登录）  yyb（远程签发服务）                       │
├────────────────────────── platform ──────────────────────────────┤
│   api（PlatformAdapter 契约）                                    │
│   pdd ｜ taobao ｜ jd ｜ sf ｜ ryll —— 各平台适配器实现          │
└──────────────────────────────────────────────────────────────────┘
```

### 登录方式

| 平台 | 方式 |
|---|---|
| 淘宝 | 手机号 + 短信验证码（App 内协议登录，含风控二次验证） |
| 京东 / 顺丰 / 拼多多 | 微信 code 换链（YYB-GO 远程签发，App 直连平台换链） |
| 如意邻里（圆通） | 手机号 + 图形验证码 + 短信验证码（全程 App 内完成） |

## 📲 下载安装

从 [**Releases**](https://github.com/njpemo/mashangqu/releases) 下载最新 APK，或直接获取当前版本：

> [mashangqu-v1.0.8.apk](https://github.com/njpemo/mashangqu/releases/download/v1.0.8/mashangqu-v1.0.8.apk)

安装后，后续新版本可通过 App 内更新自动获取。

<details>
<summary><b>从源码构建</b></summary>

- Android Studio ｜ Kotlin 2.x + Jetpack Compose ｜ minSdk 26 / targetSdk 34

```bash
cd app
.\gradlew.bat assembleDebug
```

产物位于 `app/app/build/outputs/apk/`。

</details>

<details>
<summary><b>发版机制</b></summary>

更新源为自建接口 `https://msq.l2.ink/api/check.php`（虚拟主机 PHP 代理，服务端持有蒲公英 Key，见 `server/check.php`）：

1. 修改 `update/latest.json` 版本号，`.\gradlew.bat assembleRelease` 构建；
2. APK 上传蒲公英即完成发版——check.php 自动返回最新包信息（versionCode 取包上传时间，零手动维护）；
3. 客户端语义化比对版本号，仅在有新版时弹窗提示。

</details>

## 🗂️ 项目结构

```
app/
├── app/                宿主壳：页面、导航、应用内更新
├── core/
│   ├── model/          统一数据模型与错误模型
│   ├── network/        OkHttp/Retrofit 基础配置
│   ├── storage/        Keystore 加密存储、Room 缓存、DataStore 设置
│   ├── webview/        通用登录 WebView 组件
│   └── yyb/            YYB-GO 远程服务 HTTP 客户端
└── platform/
    ├── api/            PlatformAdapter 接口契约
    └── pdd/ taobao/ jd/ sf/ ryll/   平台适配器实现
```

## ⚠️ 免责声明

本项目**仅供个人学习与自用**，不分发、不商用。凭据仅存储于本机（Keystore + EncryptedSharedPreferences 加密），永不上传。使用本项目产生的一切账号风险由使用者自行承担，请遵守各平台用户协议。

---

<div align="center">

**码上取** — 少装几个 App，快一点取到快递

</div>
