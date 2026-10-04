package com.bilideclutter

import android.util.Log
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Modifier

/**
 * 直接禁止播放页"免流"推广横幅显示（不谎报网络状态）。
 *
 * 横幅文案不在 APK 内，由服务端 gRPC 接口下发：
 *   bilibili.app.view.v1.ViewMoss/ExecuteTFInfo -> TFInfoReply
 *   （tfToast: title/description/btnText/btnLink，即"B站大会员+15G专属流量 /
 *     首月低至1分钱，免流量畅玩B站 / 我要免流"）
 *
 * 触发链（b3.20.4 实测，见反编译分析）：
 * - 经典播放器：
 *   PlayerNetworkService.onMeteredNetworkUrlHook（蜂窝网络拉流时）
 *   -> ... -> C2() 请求 ExecuteTFInfo -> g2(TFToast, tipsId) 弹横幅
 * - 新统一播放器 theseus：
 *   TheseusPlayingNetworkStrategy（监听 PlayNetworkEnv，蜂窝时）
 *   -> NetworkShowToastService.tryToShowFreeDataGuideToast
 *   -> MediaNetworkRepository.requestTFInfo -> l(TFInfo) 弹横幅
 *
 * 禁用方式（按优先级，前一层失效时后层兜底）：
 * 1. hook ViewMoss.executeTFInfo 返回 null：两条链对 null 均有判空保护，
 *    横幅静默不弹，且不影响正常播放；
 * 2. 兜底 hook 两条链的横幅渲染入口（g2 / showFreeDataGuideToast）。
 */
object FreeDataPatch {

    private const val TAG = "BiliDeclutter"

    private const val VIEW_MOSS_CLASS = "com.bapis.bilibili.app.view.v1.ViewMoss"
    private const val PLAYER_NETWORK_SERVICE =
        "com.bilibili.playerbizcommon.features.network.PlayerNetworkService"
    private const val TF_TOAST_CLASS = "com.bapis.bilibili.app.view.v1.TFToast"
    private const val TOAST_SHOW_SERVICE =
        "com.bilibili.ship.theseus.united.player.mediaplay.network.NetworkShowToastService"
    private const val TF_INFO_CLASS =
        "com.bilibili.ship.theseus.united.player.mediaplay.network.TFInfo"

    /** 返回 null 的替换器；两条链对 TFInfo/TFToast 为 null 均会静默跳过 */
    private val RETURN_NULL = object : XC_MethodReplacement() {
        override fun replaceHookedMethod(param: MethodHookParam): Any? = null
    }

    fun hook(classLoader: ClassLoader) {
        hookExecuteTFInfo(classLoader)
        hookClassicToastEntry(classLoader)
        hookTheseusToastEntry(classLoader)
    }

    /** 数据源：ExecuteTFInfo 是两条触发链共用的接口 */
    private fun hookExecuteTFInfo(classLoader: ClassLoader) {
        val mossClass = XposedHelpers.findClassIfExists(VIEW_MOSS_CLASS, classLoader) ?: run {
            Log.w(TAG, "禁用免流横幅：找不到 $VIEW_MOSS_CLASS，跳过数据源 hook")
            return
        }
        var hooked = 0
        for (method in mossClass.declaredMethods) {
            if (method.name != "executeTFInfo") continue
            runCatching { XposedBridge.hookMethod(method, RETURN_NULL) }
                .onSuccess { hooked++ }
        }
        if (hooked > 0) {
            Log.i(TAG, "禁用免流横幅：已 hook ViewMoss.executeTFInfo 的 $hooked 个重载")
        } else {
            Log.w(TAG, "禁用免流横幅：未能 hook executeTFInfo")
        }
    }

    /** 兜底（经典播放器）：PlayerNetworkService.g2(TFToast, String) 是横幅构建入口 */
    private fun hookClassicToastEntry(classLoader: ClassLoader) {
        val serviceClass = XposedHelpers.findClassIfExists(PLAYER_NETWORK_SERVICE, classLoader) ?: run {
            Log.w(TAG, "禁用免流横幅：找不到 $PLAYER_NETWORK_SERVICE，跳过经典链兜底 hook")
            return
        }
        val tfToastClass = XposedHelpers.findClassIfExists(TF_TOAST_CLASS, classLoader)
            ?: run {
                Log.w(TAG, "禁用免流横幅：找不到 $TF_TOAST_CLASS，跳过经典链兜底 hook")
                return
            }
        val entry = serviceClass.declaredMethods.firstOrNull {
            it.parameterTypes.toList() == listOf(tfToastClass, String::class.java)
        } ?: run {
            Log.w(TAG, "禁用免流横幅：经典链未找到横幅入口方法，跳过兜底 hook")
            return
        }
        runCatching { XposedBridge.hookMethod(entry, RETURN_NULL) }
            .onSuccess { Log.i(TAG, "禁用免流横幅：已 hook 经典链入口 ${serviceClass.name}.${entry.name}") }
    }

    /**
     * 兜底（theseus 播放器）：showFreeDataGuideToast 是横幅渲染入口。
     * 方法名（l）随版本混淆可能变化，按参数签名匹配（TFInfo, Continuation）。
     */
    private fun hookTheseusToastEntry(classLoader: ClassLoader) {
        val serviceClass = XposedHelpers.findClassIfExists(TOAST_SHOW_SERVICE, classLoader) ?: run {
            Log.w(TAG, "禁用免流横幅：找不到 $TOAST_SHOW_SERVICE，跳过 theseus 链兜底 hook")
            return
        }
        val tfInfoClass = XposedHelpers.findClassIfExists(TF_INFO_CLASS, classLoader) ?: run {
            Log.w(TAG, "禁用免流横幅：找不到 $TF_INFO_CLASS，跳过 theseus 链兜底 hook")
            return
        }
        val entry = serviceClass.declaredMethods.firstOrNull { method ->
            val params = method.parameterTypes
            params.size == 2 &&
                params[0] == tfInfoClass &&
                Modifier.isPrivate(method.modifiers)
        } ?: run {
            Log.w(TAG, "禁用免流横幅：theseus 链未找到横幅入口方法，跳过兜底 hook")
            return
        }
        runCatching { XposedBridge.hookMethod(entry, RETURN_NULL) }
            .onSuccess { Log.i(TAG, "禁用免流横幅：已 hook theseus 链入口 ${serviceClass.name}.${entry.name}") }
    }
}
