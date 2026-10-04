package com.bilideclutter

import android.util.Log
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Modifier

/**
 * 直接禁止"开启通知权限"提醒弹窗（不谎报任何状态）。
 *
 * 触发链（b3.20.4 实测，见反编译分析）：
 * - 入口：tv.danmaku.bili.push.guidancev2.PushGuidanceManagerKt
 *     q(Activity)  -> s(Context, String, String)          常规入口（首页 StartupFragmentV2 触发）
 *     t(AppCompatActivity, String, String, Continuation)  挂起入口（其他页面直接调用）
 * - 弹窗数据：tv.danmaku.bili.push.PushCallBackInfo（服务端下发，
 *   本地兜底文案 string/notification_setting_default_title
 *   = "哔哩哔哩"想给您发送通知）
 * - 按模板分流：popupType==1 走系统权限对话框，templateType==0 弹
 *   PushSettingDialogV2，其余经 pushsetting.ui.d.a() 弹 V3-V6 样式
 * - 埋点：push.permission-guide.popup.0.show
 *
 * 禁用方式：在入口处直接短路（q 委托给 s，s/t 是两个独立入口），
 * 弹窗分支根本不会执行。方法名（q/s/t）随版本混淆可能变化，
 * 按参数签名匹配。
 */
object PushGuidePatch {

    private const val TAG = "BiliDeclutter"

    private const val MANAGER_CLASS = "tv.danmaku.bili.push.guidancev2.PushGuidanceManagerKt"
    private const val CONTINUATION_CLASS = "kotlin.coroutines.Continuation"

    fun hook(classLoader: ClassLoader) {
        val managerClass = XposedHelpers.findClassIfExists(MANAGER_CLASS, classLoader) ?: run {
            Log.w(TAG, "禁用通知提醒：找不到 $MANAGER_CLASS，功能不生效")
            return
        }
        val continuationClass = XposedHelpers.findClassIfExists(CONTINUATION_CLASS, classLoader)
        val activityClass = XposedHelpers.findClassIfExists("android.app.Activity", classLoader)
        val appCompatClass =
            XposedHelpers.findClassIfExists("androidx.appcompat.app.AppCompatActivity", classLoader)
        val contextClass = android.content.Context::class.java

        val returnNull = object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: MethodHookParam): Any? = null
        }

        var hooked = 0
        for (method in managerClass.declaredMethods) {
            // s(Context, String, String)：showPushGuidanceDialog 主入口
            val isS = Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.toList() == listOf(contextClass, String::class.java, String::class.java)
            // q(Activity)：showPushGuidanceDialog 的便捷封装，内部委托 s
            val isQ = Modifier.isStatic(method.modifiers) && activityClass != null &&
                method.parameterTypes.toList() == listOf(activityClass)
            // t(AppCompatActivity, String, String, Continuation)：挂起入口
            val isT = Modifier.isStatic(method.modifiers) && appCompatClass != null &&
                continuationClass != null &&
                method.parameterTypes.toList() == listOf(
                    appCompatClass,
                    String::class.java,
                    String::class.java,
                    continuationClass,
                )
            if (isS || isQ || isT) {
                runCatching { XposedBridge.hookMethod(method, returnNull) }
                    .onSuccess {
                        hooked++
                        Log.i(TAG, "禁用通知提醒：已 hook ${managerClass.name}.${method.name}")
                    }
            }
        }
        if (hooked == 0) {
            Log.w(TAG, "禁用通知提醒：未匹配到任何入口方法（q/s/t），功能不生效")
        }
    }
}
