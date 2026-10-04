package com.bilideclutter

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Modifier

/**
 * 移除部分直播间官方添加的马赛克遮罩。
 *
 * 移植自 BiliRoamingX 的 remove_live_mask 功能：
 * 直播间信息接口返回的 `area_mask_info` 字段会让客户端在直播画面上
 * 叠加官方马赛克遮罩。这里 hook fastjson 的 `JSON.parseObject`，
 * 在反序列化之后把 `BiliLiveRoomInfo.areaMaskInfo` 置空，遮罩即不再渲染。
 *
 * 对应 BiliRoamingX 中的逻辑（JSONPatch.parseObjectHookInternal）：
 * ```
 * if (Settings.RemoveLiveMask.get()) try {
 *     roomInfo.areaMaskInfo = null;
 * } catch (Throwable ignored) {
 * }
 * ```
 */
object LiveMaskPatch {

    private const val TAG = "BiliDeclutter"

    private const val JSON_CLASS = "com.alibaba.fastjson.JSON"
    private const val FEATURE_CLASS = "com.alibaba.fastjson.parser.Feature"
    private const val GENERAL_RESPONSE_CLASS = "com.bilibili.okretro.GeneralResponse"
    private const val ROOM_INFO_CLASS =
        "com.bilibili.bililive.videoliveplayer.net.beans.gateway.roominfo.BiliLiveRoomInfo"
    private const val MASK_FIELD = "areaMaskInfo"

    fun hook(classLoader: ClassLoader) {
        val jsonClass = XposedHelpers.findClassIfExists(JSON_CLASS, classLoader) ?: run {
            Log.w(TAG, "移除直播马赛克：找不到 $JSON_CLASS，功能不生效")
            return
        }
        val roomInfoClass = XposedHelpers.findClassIfExists(ROOM_INFO_CLASS, classLoader) ?: run {
            Log.w(TAG, "移除直播马赛克：找不到 $ROOM_INFO_CLASS，功能不生效")
            return
        }
        // okretro 的通用响应包装，业务对象在 GeneralResponse<T>.data 里
        val generalResponseClass =
            XposedHelpers.findClassIfExists(GENERAL_RESPONSE_CLASS, classLoader)

        // BiliRoamingX 注入的三个 parseObject 重载；按参数签名匹配，
        // 这样即使目标 App 混淆了方法名（BiliRoamingX 里的 "a" 分支）也能命中
        val featureArrayClass = XposedHelpers.findClassIfExists(FEATURE_CLASS, classLoader)
            ?.let { java.lang.reflect.Array.newInstance(it, 0).javaClass }
        val typeClass = java.lang.reflect.Type::class.java
        val targetSignatures = listOfNotNull(
            featureArrayClass?.let {
                listOf(String::class.java, typeClass, Int::class.javaPrimitiveType!!, it)
            },
            featureArrayClass?.let { listOf(String::class.java, typeClass, it) },
            listOf(String::class.java, java.lang.Class::class.java),
        )

        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val result = param.result ?: return
                try {
                    val data = if (generalResponseClass?.isInstance(result) == true) {
                        runCatching { XposedHelpers.getObjectField(result, "data") }.getOrNull()
                    } else {
                        result
                    }
                    if (data != null && roomInfoClass.isInstance(data)) {
                        // 字段缺失时静默忽略，与 BiliRoamingX 的 try/catch 一致
                        runCatching { XposedHelpers.setObjectField(data, MASK_FIELD, null) }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "移除直播马赛克 hook 异常", t)
                }
            }
        }

        var hooked = 0
        for (method in jsonClass.declaredMethods) {
            if (!Modifier.isStatic(method.modifiers)) continue
            val params = method.parameterTypes
            val byName = method.name == "parseObject" && params.firstOrNull() == String::class.java
            val bySignature = params.toList() in targetSignatures
            if (byName || bySignature) {
                XposedBridge.hookMethod(method, hook)
                hooked++
            }
        }
        Log.i(TAG, "移除直播马赛克：已 hook $JSON_CLASS 的 $hooked 个 parseObject 方法")
    }
}
