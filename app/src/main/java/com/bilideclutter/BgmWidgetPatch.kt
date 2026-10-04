package com.bilideclutter

import android.util.Log
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 删除播放页右下角的音符悬浮按钮（BGM 半屏面板入口，容易误触）。
 *
 * 控件（b3.20.4 实测，见反编译分析）：
 *   com.bilibili.app.gemini.ugc.feature.GeminiPlayerBgmWidget
 *   （FrameLayout，内含 26dp 静态音符 BiliImageView / 展开 SVGAIconView，
 *     位置 Location.BOTTOM_RIGHT）
 *
 * 显示条件：
 *  - 数据：互动层 viewprogress 接口返回的 videoGuide.rightMaterial
 *    （MaterialBizType.NEW_BGM / GENERAL_TYPE，即服务端识别到音频才下发）；
 *  - 总开关：blconfig videodetail.bgm_halfplayer_location == "2"。
 * 点击 → mActionDelegate.u(url+"&position_id=1") → showBgmWebPanel 打开
 * BGM 音乐半屏 Web 面板（埋点 player.player.activity.show/click.player）。
 *
 * 禁用方式（两层，均锚定非混淆的稳定类名）：
 * 1. 数据源：hook VideoGuide.getRightMaterial() 返回 null。
 *    该 getter 的唯一消费者就是此按钮，置空后 widget 所有入口
 *    （挂载/控制栏显隐/进度更新）都会因 data==null 走隐藏分支，
 *    按钮根本不会创建；
 * 2. 兜底：hook 控件的 setVisibility(int) 强制传 GONE，
 *    即使数据源 hook 因版本变化失效，按钮也永远不可见。
 *
 * 注意不能 hook Material.getType()：MaterialBizType 还被
 * GeminiPlayerOperationWidget、UGCActionDelegate 等其他功能共用。
 */
object BgmWidgetPatch {

    private const val TAG = "BiliDeclutter"

    private const val VIDEO_GUIDE_CLASS =
        "tv.danmaku.biliplayerv2.service.interact.biz.model.viewprogress.uniteviewprogress.VideoGuide"
    private const val WIDGET_CLASS = "com.bilibili.app.gemini.ugc.feature.GeminiPlayerBgmWidget"

    fun hook(classLoader: ClassLoader) {
        hookRightMaterial(classLoader)
        hookWidgetVisibility(classLoader)
    }

    /** 数据源：rightMaterial 只服务于右下角 BGM/运营悬浮按钮 */
    private fun hookRightMaterial(classLoader: ClassLoader) {
        val guideClass = XposedHelpers.findClassIfExists(VIDEO_GUIDE_CLASS, classLoader) ?: run {
            Log.w(TAG, "删除音符按钮：找不到 $VIDEO_GUIDE_CLASS，跳过数据源 hook")
            return
        }
        val getter = guideClass.declaredMethods.firstOrNull {
            it.name == "getRightMaterial" && it.parameterTypes.isEmpty()
        } ?: run {
            Log.w(TAG, "删除音符按钮：VideoGuide 中未找到 getRightMaterial，跳过数据源 hook")
            return
        }
        val returnNull = object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: MethodHookParam): Any? = null
        }
        runCatching { XposedBridge.hookMethod(getter, returnNull) }
            .onSuccess { Log.i(TAG, "删除音符按钮：已 hook VideoGuide.getRightMaterial") }
    }

    /** 兜底：无论谁想让按钮显示，一律改成 GONE */
    private fun hookWidgetVisibility(classLoader: ClassLoader) {
        val widgetClass = XposedHelpers.findClassIfExists(WIDGET_CLASS, classLoader) ?: run {
            Log.w(TAG, "删除音符按钮：找不到 $WIDGET_CLASS，跳过兜底 hook")
            return
        }
        val setter = widgetClass.declaredMethods.firstOrNull {
            it.name == "setVisibility" &&
                it.parameterTypes.toList() == listOf(Int::class.javaPrimitiveType)
        } ?: run {
            Log.w(TAG, "删除音符按钮：GeminiPlayerBgmWidget 中未找到 setVisibility(int)，跳过兜底 hook")
            return
        }
        val forceGone = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.args[0] = View.GONE
            }
        }
        runCatching { XposedBridge.hookMethod(setter, forceGone) }
            .onSuccess { Log.i(TAG, "删除音符按钮：已 hook GeminiPlayerBgmWidget.setVisibility 强制 GONE") }
    }
}
