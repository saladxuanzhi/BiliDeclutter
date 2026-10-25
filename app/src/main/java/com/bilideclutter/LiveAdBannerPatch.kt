package com.bilideclutter

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Modifier

/**
 * 屏蔽直播间右下角的运营/广告横幅。
 *
 * 直播间信息接口（getInfoByRoom）返回的 `BiliLiveRoomInfo.bannerInfo`
 * （JSON 字段 `activity_banner_info`）承载了右下角横幅广告位的全部数据，
 * 由 `LiveRoomOperationViewModelV3` 分发渲染：
 *
 *  - `normal_banner`  → 右下角轮播横幅（"秋日工坊"、"影之刃零"等运营/游戏广告）
 *  - `pendant_banner` → 挂件横幅（悬浮在播放器右侧的广告位）
 *  - `proactive_banner` → 主动弹出的推广横幅
 *
 * 本模块在 fastjson 反序列化出 `BiliLiveRoomInfo` 之后直接清理这三路数据：
 * 消费端对 null / 空列表均有判空保护，横幅不会创建，底部收起箭头也随之消失。
 *
 * `normal_banner` 中 `type == 9999` 的条目是"MV 播放中"通知（非广告），予以保留。
 *
 * hook 点与 [LiveMaskPatch] 相同（fastjson parseObject），两个功能各自独立、互不影响。
 */
object LiveAdBannerPatch {

    private const val TAG = "BiliDeclutter"

    private const val JSON_CLASS = "com.alibaba.fastjson.JSON"
    private const val FEATURE_CLASS = "com.alibaba.fastjson.parser.Feature"
    private const val GENERAL_RESPONSE_CLASS = "com.bilibili.okretro.GeneralResponse"
    private const val ROOM_INFO_CLASS =
        "com.bilibili.bililive.videoliveplayer.net.beans.gateway.roominfo.BiliLiveRoomInfo"

    /** BiliLiveRoomInfo.bannerInfo（activity_banner_info） */
    private const val FIELD_BANNER_INFO = "bannerInfo"
    /** BiliLiveRoomBanner.normalBanner（normal_banner 轮播横幅列表） */
    private const val FIELD_NORMAL_BANNER = "normalBanner"
    /** BiliLiveRoomBanner.pendantBannerInfo（pendant_banner 挂件横幅列表） */
    private const val FIELD_PENDANT_BANNER = "pendantBannerInfo"
    /** BiliLiveRoomBanner.activeBanner（proactive_banner 主动弹出横幅） */
    private const val FIELD_ACTIVE_BANNER = "activeBanner"
    /** BiliLiveNormalBanner.type：9999 为"MV 播放中"通知，非广告，保留 */
    private const val TYPE_MV_PLAYING = 9999

    fun hook(classLoader: ClassLoader) {
        val jsonClass = XposedHelpers.findClassIfExists(JSON_CLASS, classLoader) ?: run {
            Log.w(TAG, "屏蔽直播横幅广告：找不到 $JSON_CLASS，功能不生效")
            return
        }
        val roomInfoClass = XposedHelpers.findClassIfExists(ROOM_INFO_CLASS, classLoader) ?: run {
            Log.w(TAG, "屏蔽直播横幅广告：找不到 $ROOM_INFO_CLASS，功能不生效")
            return
        }
        // okretro 的通用响应包装，业务对象在 GeneralResponse<T>.data 里
        val generalResponseClass =
            XposedHelpers.findClassIfExists(GENERAL_RESPONSE_CLASS, classLoader)

        // 与 LiveMaskPatch 相同：按参数签名匹配 parseObject 重载，兼容方法名混淆
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
                        cleanBannerInfo(data)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "屏蔽直播横幅广告 hook 异常", t)
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
        Log.i(TAG, "屏蔽直播横幅广告：已 hook $JSON_CLASS 的 $hooked 个 parseObject 方法")
    }

    /**
     * 清理 BiliLiveRoomInfo.bannerInfo 中的广告横幅数据。
     * 字段缺失或类型变化时静默忽略，不影响正常反序列化。
     */
    private fun cleanBannerInfo(roomInfo: Any) {
        val bannerInfo = runCatching {
            XposedHelpers.getObjectField(roomInfo, FIELD_BANNER_INFO)
        }.getOrNull() ?: return

        // 1. normal_banner：仅保留 type==9999 的"MV 播放中"通知，其余（广告/运营横幅）移除
        runCatching {
            val normalBanner = XposedHelpers.getObjectField(bannerInfo, FIELD_NORMAL_BANNER)
            if (normalBanner is MutableList<*>) {
                (normalBanner as MutableCollection<Any?>).removeAll { item ->
                    runCatching {
                        XposedHelpers.getIntField(item, "type") != TYPE_MV_PLAYING
                    }.getOrDefault(true)
                }
            }
        }

        // 2. pendant_banner：挂件横幅整组清空
        runCatching {
            val pendantBanner = XposedHelpers.getObjectField(bannerInfo, FIELD_PENDANT_BANNER)
            if (pendantBanner is MutableList<*>) {
                (pendantBanner as MutableCollection<*>).clear()
            }
        }

        // 3. proactive_banner：主动弹出横幅置空
        runCatching {
            XposedHelpers.setObjectField(bannerInfo, FIELD_ACTIVE_BANNER, null)
        }
    }
}
