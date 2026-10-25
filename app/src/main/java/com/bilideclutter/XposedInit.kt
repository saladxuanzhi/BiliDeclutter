package com.bilideclutter

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * 禁用哔哩哔哩的"免流"推广横幅和"开启通知权限"提醒弹窗，
 * 并删除播放页右下角的音符悬浮按钮。
 *
 * 直接在显示入口/数据源处短路，不修改 App 对网络状态的感知：
 *  - 免流横幅：拦截 ViewMoss.ExecuteTFInfo（服务端推广文案接口），
 *    见 [FreeDataPatch]
 *  - 通知提醒弹窗：短路 PushGuidanceManagerKt 的弹窗入口，
 *    见 [PushGuidePatch]
 *  - 音符悬浮按钮（BGM 半屏面板入口）：置空 VideoGuide.rightMaterial
 *    并强制控件 GONE，见 [BgmWidgetPatch]
 *
 * 另外移植 BiliRoamingX 的 remove_live_mask：
 *  - 反序列化后置空 BiliLiveRoomInfo.areaMaskInfo，见 [LiveMaskPatch]
 *
 * 屏蔽直播间右下角的运营/广告横幅：
 *  - 反序列化后清理 BiliLiveRoomInfo.bannerInfo 的三路横幅数据，见 [LiveAdBannerPatch]
 */
class XposedInit : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName !in TARGET_PACKAGES) return
        // 只处理主进程，避免多余 hook 影响子进程
        if (lpparam.processName != lpparam.packageName) return

        Log.i(TAG, "hook 生效于 ${lpparam.packageName}")

        // 1. 禁用播放页"免流"推广横幅（含网络限制面板的推广 toast）
        FreeDataPatch.hook(lpparam.classLoader)

        // 2. 禁用"开启通知权限"提醒弹窗
        PushGuidePatch.hook(lpparam.classLoader)

        // 3. 删除播放页右下角的音符悬浮按钮（BGM 半屏面板入口）
        BgmWidgetPatch.hook(lpparam.classLoader)

        // 4. 移除部分直播间官方添加的马赛克遮罩（移植自 BiliRoamingX）
        LiveMaskPatch.hook(lpparam.classLoader)

        // 5. 屏蔽直播间右下角的运营/广告横幅
        LiveAdBannerPatch.hook(lpparam.classLoader)
    }

    private companion object {
        const val TAG = "BiliDeclutter"

        val TARGET_PACKAGES = setOf(
            "tv.danmaku.bili",
            "com.bilibili.app.blue",
            "com.bilibili.app.in",
            "tv.danmaku.bilibilihd",
        )
    }
}
