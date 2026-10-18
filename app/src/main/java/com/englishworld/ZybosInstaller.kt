package com.englishworld

import android.content.Context
import android.util.Log
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 隐藏 API 反射绕过。
 *
 * Android 9 起，非 SDK（hidden）成员的反射会被系统策略拦截（NoSuchMethodException）。
 * 这里先用 dalvik.system.VMRuntime#setHiddenApiExemptions(["L"]) 解除限制，
 * 再按调用方给出的签名取方法；签名不匹配时自动尝试「装箱类型 → 原始类型」转换，
 * 最后沿继承链按方法名 + 参数个数松匹配兜底。
 */
class RestrictionBypass {

    /**
     * 取声明方法（与调用方给定的参数类型精确匹配优先）。
     *
     * @param clazz      目标类（如 android.os.ZybosSysApiManager）
     * @param name       方法名
     * @param paramTypes 参数类型，如 String.class、Boolean.class
     */
    fun getDeclaredMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): Method {
        unlockHiddenApiIfNeeded()

        // 1. 原样签名
        try {
            return accessible(clazz.getDeclaredMethod(name, *paramTypes))
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "原签名未命中 ${clazz.name}#$name(${describe(paramTypes)})，尝试原始类型")
        }

        // 2. 装箱类型换成原始类型（Boolean.class -> boolean）
        val primitives = paramTypes.map { toPrimitive(it) }.toTypedArray()
        if (!(primitives contentEquals paramTypes)) {
            try {
                return accessible(clazz.getDeclaredMethod(name, *primitives))
            } catch (e: NoSuchMethodException) {
                // 继续降级
            }
        }

        // 3. 继承链 + 名称/参数个数松匹配
        findLoose(clazz, name, paramTypes)?.let { return it }
        findLoose(clazz, name, primitives)?.let { return it }

        throw NoSuchMethodException("${clazz.name}#$name(${describe(paramTypes)}) 不存在或不可访问")
    }

    // ---------------- 内部实现 ----------------

    private fun accessible(method: Method): Method {
        method.isAccessible = true
        return method
    }

    private fun findLoose(clazz: Class<*>, name: String, paramTypes: Array<out Class<*>>): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            val declared = try {
                current.declaredMethods
            } catch (t: Throwable) {
                emptyArray<Method>()
            }
            val matched = declared.firstOrNull { method ->
                method.name == name &&
                    method.parameterTypes.size == paramTypes.size &&
                    method.parameterTypes.indices.all { i ->
                        normalize(method.parameterTypes[i]) == normalize(paramTypes[i])
                    }
            }
            if (matched != null) return accessible(matched)
            current = current.superclass
        }
        return null
    }

    private fun normalize(type: Class<*>): String = when (type) {
        java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> "boolean"
        java.lang.Integer.TYPE, java.lang.Integer::class.java -> "int"
        java.lang.Long.TYPE, java.lang.Long::class.java -> "long"
        else -> type.name
    }

    private fun toPrimitive(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean::class.java -> java.lang.Boolean.TYPE
        java.lang.Integer::class.java -> java.lang.Integer.TYPE
        java.lang.Long::class.java -> java.lang.Long.TYPE
        else -> type
    }

    private fun describe(paramTypes: Array<out Class<*>>): String =
        paramTypes.joinToString(", ") { it.simpleName }

    /**
     * 解除 Android 9+ 对隐藏 API 的反射限制（FreeReflection 思路）：
     * 通过 java.lang.Class 自身的公开方法拿到 VMRuntime，再设置
     * setHiddenApiExemptions(["L"]) 豁免全部 L 开头（即所有 Java/Kotlin）成员。
     */
    @Suppress("PrivateApi", "DiscouragedPrivateApi")
    private fun unlockHiddenApiIfNeeded() {
        if (hiddenApiUnlocked) return
        try {
            val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
            // 注意：Kotlin 不允许 Array<Class<*>>::class 这类泛型类字面量，用实例取运行时类
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java
            )
            val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            // 第二个参数传 null 表示无参方法
            val getRuntime = getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null) as Method
            val setExemptions = getDeclaredMethod.invoke(
                vmRuntimeClass, "setHiddenApiExemptions", arrayOf<Class<*>>(arrayOf<String>()::class.java)
            ) as Method
            getRuntime.isAccessible = true
            setExemptions.isAccessible = true
            val runtime = getRuntime.invoke(null)
            setExemptions.invoke(runtime, *arrayOf<Any>(arrayOf("L")))
            hiddenApiUnlocked = true
            Log.i(TAG, "已解除隐藏 API 反射限制")
        } catch (t: Throwable) {
            Log.w(TAG, "解除隐藏 API 限制失败：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "RestrictionBypass"

        @Volatile
        private var hiddenApiUnlocked = false
    }
}

/**
 * ZybOS 设备官方安装接口封装，严格按官方文档的调用顺序执行：
 *
 *     val zybosService = context.getSystemService("zybos_manager")
 *     val zybosCls = Class.forName("android.os.ZybosSysApiManager")
 *     val bypass = RestrictionBypass()
 *     bypass.getDeclaredMethod(zybosCls, "setInstallPackageDisallow", Boolean::class.java)
 *         .invoke(zybosService, false)                      // 解除安装封禁
 *     bypass.getDeclaredMethod(zybosCls, "silentInstallApk",
 *         String::class.java, Boolean::class.java, String::class.java)
 *         .invoke(zybosService, apkFile.absolutePath, true, packageName)   // 静默安装
 *
 * 任何一步失败都不会崩溃，只返回失败原因，由调用方决定是否回退到系统安装器。
 */
object ZybosInstaller {

    /** 定制系统服务名 */
    const val SERVICE_NAME = "zybos_manager"

    /** 系统定制管理类全名 */
    const val MANAGER_CLASS = "android.os.ZybosSysApiManager"

    private const val METHOD_DISALLOW = "setInstallPackageDisallow"
    private const val METHOD_INSTALL = "silentInstallApk"

    private const val TAG = "ZybosInstaller"

    /** 安装结果：success=true 表示系统服务已接收安装请求 */
    data class Outcome(val success: Boolean, val message: String)

    /** 设备是否提供官方安装接口 */
    fun isAvailable(context: Context): Boolean = serviceOf(context) != null

    /**
     * 开发者自检：验证隐藏 API 反射绕过是否真的生效。
     *
     * 用一个必然被系统隐藏 API 策略拦截的方法（ActivityManager#forceStopPackage）
     * 作为探针，只做反射取方法、不调用，不影响任何正常流程。
     */
    fun hiddenApiSelfTest(): String {
        return try {
            val method = RestrictionBypass().getDeclaredMethod(
                Class.forName("android.app.ActivityManager"),
                "forceStopPackage",
                String::class.java
            )
            "可用（已取到 ${method.name}）"
        } catch (t: Throwable) {
            "不可用：${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /**
     * 通过官方接口静默安装指定 APK。
     *
     * @param apkPath     待安装 APK 的绝对路径
     * @param packageName 目标包名，随安装请求一并发给系统服务
     */
    fun silentInstall(context: Context, apkPath: String, packageName: String): Outcome {
        // 1. 获取系统定制服务
        val zybosService = serviceOf(context)
            ?: return Outcome(false, "本机未提供官方安装接口（$SERVICE_NAME 服务不存在）")

        val apk = File(apkPath)
        if (!apk.isFile || apk.length() <= 0L) {
            return Outcome(false, "安装包不存在或为空")
        }

        return try {
            // 2. 绕过 Hidden API 限制
            val bypass = RestrictionBypass()
            val zybosCls = Class.forName(MANAGER_CLASS)

            // 3. 解除安装封禁
            try {
                val disallowInstallMethod =
                    bypass.getDeclaredMethod(zybosCls, METHOD_DISALLOW, Boolean::class.java)
                disallowInstallMethod.invoke(zybosService, false)
                Log.i(TAG, "$METHOD_DISALLOW(false) 执行成功，已解除安装封禁")
            } catch (t: Throwable) {
                // 解除封禁失败不阻断安装，仅记录日志
                Log.w(TAG, "$METHOD_DISALLOW 调用失败：${describe(t)}")
            }

            // 4. 执行静默安装
            val installMethod = bypass.getDeclaredMethod(
                zybosCls, METHOD_INSTALL,
                String::class.java, Boolean::class.java, String::class.java
            )
            val result = invokeInstall(installMethod, zybosService, apk.absolutePath, true, packageName)
            if (result is Boolean && !result) {
                Log.w(TAG, "$METHOD_INSTALL 返回 false")
                Outcome(false, "系统服务拒绝了安装请求")
            } else {
                Log.i(TAG, "$METHOD_INSTALL 已提交：$apkPath -> $packageName")
                Outcome(true, "已通过官方接口提交安装")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "$METHOD_INSTALL 调用失败：${describe(t)}")
            Outcome(false, "安装调用失败：${describe(t)}")
        }
    }

    // ---------------- 内部实现 ----------------

    /**
     * 执行安装调用。
     *
     * 部分设备上 getSystemService 返回的是 Binder 代理对象，其真实类型并非
     * android.os.ZybosSysApiManager 本身，此时用 Class.forName 取出的方法 invoke
     * 会抛 IllegalArgumentException；这种情况退回用服务对象的真实类型反射。
     */
    private fun invokeInstall(
        method: Method,
        service: Any,
        path: String,
        replace: Boolean,
        packageName: String
    ): Any? {
        return try {
            method.invoke(service, path, replace, packageName)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "方法声明类型与服务对象不匹配，改用服务对象真实类型：${e.message}")
            val actual = RestrictionBypass().getDeclaredMethod(
                service.javaClass, METHOD_INSTALL,
                String::class.java, Boolean::class.java, String::class.java
            )
            actual.invoke(service, path, replace, packageName)
        }
    }

    private fun serviceOf(context: Context): Any? {
        return try {
            context.getSystemService(SERVICE_NAME)
        } catch (t: Throwable) {
            Log.w(TAG, "获取 $SERVICE_NAME 失败：${describe(t)}")
            null
        }
    }

    /** 拆解 InvocationTargetException，输出真实原因 */
    private fun describe(t: Throwable): String {
        val real = if (t is InvocationTargetException) (t.targetException ?: t) else t
        return if (real.message.isNullOrBlank()) real.javaClass.simpleName
        else "${real.javaClass.simpleName}: ${real.message}"
    }
}
