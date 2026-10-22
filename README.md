# 码上取 — 多平台身份码聚合 App

> 把拼多多 / 淘宝 / 京东 / 顺丰 / 如意邻里五个平台的「取件身份码」和快递取件信息聚合到一个 App，一站出示，告别来回切换。

无后端、纯本地运行的 Android 应用：所有请求由 App 直连各平台接口，凭据经 Android Keystore 加密仅存本机，不上传任何服务器。

## 功能

- **身份码聚合**：五平台取件身份码集中展示，本地渲染二维码/条码，下拉刷新 + 可配置自动刷新；顺丰身份码已实现本地公式生成，长期免调用
- **快递列表**：各平台站点快递聚合展示，按平台/站点筛选，跨平台按运单号去重合并；本地缓存支持离线查看
- **互补查码**：某平台查不到取件码时，自动用运单号到其他平台互补查询，命中渠道会被记住
- **登录管理**：淘宝、如意邻里为 App 内手机号+短信验证码协议登录；京东/顺丰/拼多多经自部署的 YYB-GO 服务签发微信 code；登录态失效自动标红，一键重登
- **运行日志**：「我的」页实时日志面板，一键复制全部日志用于排查
- **应用内更新**：接入蒲公英，启动自动检查新版本，应用内下载 APK 并拉起安装

## 下载

前往 [Releases](https://github.com/njpemo/mashangqu/releases) 下载最新 APK，或在蒲公英搜索「码上取」。

## 技术栈

Kotlin 2.x + Jetpack Compose · Hilt · OkHttp/Retrofit · kotlinx.serialization · Room/EncryptedSharedPreferences · ZXing

```
:app                宿主壳：导航、三 Tab 页面、Hilt 装配
:core:model         统一数据模型与错误模型
:core:network       OkHttp/Retrofit 基础配置
:core:storage       凭据加密存储 + 快递缓存
:core:yyb           YYB-GO 远程服务 HTTP 客户端（微信 code 签发）
:platform:api       PlatformAdapter 统一接口契约
:platform:pdd / taobao / jd / sf / ryll   各平台适配器（互不依赖）
```

新增平台只需实现 `PlatformAdapter` 并注册，`:app` 无需改动。

## 构建

```bash
cd app
.\gradlew.bat assembleDebug
```

产物：`app/app/build/outputs/apk/debug/app-debug.apk`（versionCode/versionName 按构建时刻自动递增）。

## 免责声明

本项目仅供个人学习与自用，不分发、不商用。凭据仅存储于本机加密存储，卸载 App 或「清除全部数据」即彻底删除。各平台接口随时可能变更或加强风控，使用本项目产生的一切账号风险由使用者自行承担，请遵守各平台用户协议。
