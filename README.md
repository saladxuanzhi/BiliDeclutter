# BiliDeclutter

一个 [LSPosed](https://github.com/LSPosed/LSPosed) 模块，直接**禁用哔哩哔哩播放页的"免流"推广横幅**和**"开启通知权限"提醒弹窗**，并**删除播放页右下角易误触的音符悬浮按钮**。另外移除部分直播间官方添加的**马赛克遮罩**，并屏蔽直播间右下角的**运营/广告横幅**。

## 禁用免流推广横幅

播放页上方的横幅（"B站大会员+15G专属流量 / 首月低至1分钱，免流量畅玩B站 / 我要免流"）文案由服务端 gRPC 接口 `bilibili.app.view.v1.ViewMoss/ExecuteTFInfo` 下发，客户端在**蜂窝网络下拉流**时触发展示。

本模块不做网络状态欺骗，而是直接在数据源和显示入口处短路：

- hook `ViewMoss.executeTFInfo` 返回 `null`（新旧两套播放器触发链共用此接口，返回 null 后客户端判空跳过，不影响正常播放）；
- 兜底 hook 两条触发链的横幅渲染入口：
  - 经典播放器：`com.bilibili.playerbizcommon.features.network.PlayerNetworkService`
  - 新统一播放器 theseus：`com.bilibili.ship.theseus.united.player.mediaplay.network.NetworkShowToastService`

## 禁用通知权限提醒弹窗

"哔哩哔哩"想给您发送通知"弹窗由 `tv.danmaku.bili.push.guidancev2.PushGuidanceManagerKt` 管理，首页启动时触发，弹窗文案/模板由服务端下发（`PushCallBackInfo`）。

本模块在弹窗入口方法（`s`/`q`/`t`，按参数签名匹配以兼容混淆）处直接短路，弹窗分支不会执行。

## 删除播放页音符悬浮按钮

部分视频（服务端识别到背景音乐时）会在播放页右下角出现一个易误触的音符小按钮，点击打开 BGM 音乐半屏 Web 面板。

按钮本体是 `com.bilibili.app.gemini.ugc.feature.GeminiPlayerBgmWidget`，数据来自视频互动层 viewprogress 接口的 `videoGuide.rightMaterial`（`MaterialBizType.NEW_BGM`），显示开关是服务端配置 `videodetail.bgm_halfplayer_location`。本模块：

- hook `VideoGuide.getRightMaterial()` 返回 `null`（该字段的唯一消费者就是此按钮），按钮根本不会创建；
- 兜底 hook 控件的 `setVisibility(int)` 强制 `GONE`，即使版本变化导致数据源 hook 失效，按钮也永远不可见。

## 移除直播马赛克遮罩

移植自 [BiliRoamingX](https://github.com/BiliRoamingX/BiliRoamingX) 的「移除马赛克遮罩」（`remove_live_mask`）功能。

部分直播间的画面会被官方叠加马赛克遮罩，这是直播间信息接口返回的 `area_mask_info` 字段触发的。本模块 hook 目标 App 内置的 fastjson `JSON.parseObject`，在反序列化之后把 `BiliLiveRoomInfo.areaMaskInfo` 置空，客户端不再渲染遮罩。

- 该功能默认开启，无需配置；仅对存在相关类的目标 App 生效，不影响其他版本。
- 与 BiliRoamingX 的行为一致：只置空 `area_mask_info`，不修改其他直播间数据。

## 屏蔽直播间右下角广告横幅

直播间右下角（弹幕输入框上方）会轮播运营/广告横幅（如"秋日工坊"、"影之刃零"等），右侧还有可收起的挂件横幅。这些数据全部来自直播间信息接口 `getInfoByRoom` 返回的 `BiliLiveRoomInfo.bannerInfo`（JSON 字段 `activity_banner_info`），由 `LiveRoomOperationViewModelV3` 分发渲染：

| JSON 字段 | 客户端字段 | 展示位置 |
|---|---|---|
| `normal_banner` | `normalBanner` | 右下角轮播横幅（含 ▼ 收起箭头） |
| `pendant_banner` | `pendantBannerInfo` | 播放器右侧挂件横幅 |
| `proactive_banner` | `activeBanner` | 主动弹出的推广横幅 |

本模块与移除马赛克共用同一 hook 点（fastjson `JSON.parseObject`），在反序列化出 `BiliLiveRoomInfo` 后直接清理这三路数据。消费端对 null / 空列表均有判空保护，横幅控件不会创建，收起箭头也随之消失。

## 支持的目标 App

| 包名 | 说明 |
|---|---|
| `tv.danmaku.bili` | 哔哩哔哩 主版本 |
| `com.bilibili.app.blue` | 哔哩哔哩 概念版 |
| `com.bilibili.app.in` | 哔哩哔哩 国际版 |
| `tv.danmaku.bilibilihd` | 哔哩哔哩 HD |

hook 点基于哔哩哔哩 b3.20.4 反编译分析得出，类名/方法名随版本变化时，按参数签名匹配的兜底 hook 仍可生效，数据源 hook 失效时会打 log 提示。

## 使用方法

1. 安装 APK：在 Actions 中下载最新构建
2. 打开 LSPosed（管理器），进入「模块」，勾选 **BiliDeclutter**
3. 重启bilibili app

> 注意：当前 APK 用的是**临时 debug 签名**（无需任何配置即可构建）。后续更新 APK 时如果签名不同，系统会拒绝覆盖安装，需要先卸载旧版再装新版（LSPosed 里需重新勾选启用）。

## 本地构建

需要 JDK 17 + Android SDK（platform-35 / build-tools 35.0.0），SDK 路径写在 `local.properties`：

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

`app/build.gradle.kts` 里已保证 release 一定有签名：
- 提供了 `signingStoreFile/signingStorePassword/signingKeyAlias/signingKeyPassword` 这四个 gradle property 时，用正式 keystore 签名；
- 否则自动回退到 debug keystore（临时签名）。

## AI声明

本项目由AI参与完成。
