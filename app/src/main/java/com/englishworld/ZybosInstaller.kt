package com.englishworld

import android.content.Context
import android.util.Log
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * ZybOS 设备官方安装接口封装。
 *
 * 设备 system_server 中注册了定制系统服务 "zybos_manager"，其实现类为
 * android.os.ZybosSysApiManager。应用侧通过
 *
 *     context.getSystemService("zybos_manager")
 *
 * 取得服务对象后即可调用：
 *  - setInstallPackageDisallow(Boolean)            解除 / 开启安装封禁
 *  - silentInstallApk(path, Boolean, packageName)  静默安装 APK
 *
 * 由于该管理类属于非 SDK（hidden）接口，Android 9 起反射会被隐藏 API 策略拦截，
 * 因此这里采用「先直接反射，失败则用 dalvik.system.VMRuntime#setHiddenApiExemptions
 * 解除限制后重试」的逐级降级策略，任何一步失败都不会崩溃，只会返回失败原因，
 * 由调用方决定是否回退到系统安装器。
 */
object ZybosInstaller {

    private const val TAG = "ZybosInstaller"

    /** 定制系统服务名 */
    const val SERVICE_NAME = "zybos_manager"

    /** 系统定制管理类全名（用于日志比对，实际以服务对象的真实类型为准） */
    private const val MANAGER_CLASS = "android.os.ZybosSysApiManager"

    private const val METHOD_DISALLOW = "setInstallPackageDisallow"
    private const val METHOD_INSTALL = "silentInstallApk"

    private val BOOLEAN_TYPE: Class<*> = java.lang.Boolean.TYPE

    /** 安装结果：success=true 表示系统服务已接收安装请求 */
    data class Outcome(val success: Boolean, val message: String)

    @Volatile
    private var hiddenApiUnlocked = false

    /** 设备是否提供官方安装接口 */
    fun isAvailable(context: Context): Boolean = serviceOf(context) != null

    /**
     * 通过官方接口静默安装指定 APK。
     *
     * @param apkPath     待安装 APK 的绝对路径
     * @param packageName 目标包名，随安装请求一并发给系统服务
     */
    fun silentInstall(context: Context, apkPath: String, packageName: String): Outcome {
        val service = serviceOf(context)
            ?: return Outcome(false, "本机未提供官方安装接口（$SERVICE_NAME 服务不存在）")

        val apk = File(apkPath)
        if (!apk.isFile || apk.length() <= 0L) {
            return Outcome(false, "安装包不存在或为空")
        }

        val serviceClass = service.javaClass
        if (serviceClass.name != MANAGER_CLASS) {
            Log.i(TAG, "服务实现类为 ${serviceClass.name}，与预期 $MANAGER_CLASS 不同，按实际类型反射")
        }

        // 1. 解除安装封禁：失败不阻断安装，仅记录日志
        val disallowMethod = findMethod(serviceClass, METHOD_DISALLOW, BOOLEAN_TYPE)
        if (disallowMethod != null) {
            try {
                disallowMethod.invoke(service, false)
                Log.i(TAG, "$METHOD_DISALLOW(false) 执行成功，已解除安装封禁")
            } catch (t: Throwable) {
                Log.w(TAG, "$METHOD_DISALLOW 调用失败：${describe(t)}")
            }
        } else {
            Log.w(TAG, "未找到 $METHOD_DISALLOW，跳过解除封禁")
        }

        // 2. 静默安装
        val installMethod = findMethod(
            serviceClass, METHOD_INSTALL,
            String::class.java, BOOLEAN_TYPE, String::class.java
        ) ?: return Outcome(false, "系统接口不可用：未找到 $METHOD_INSTALL")

        return try {
            // 第二个参数 true：覆盖安装（升级本应用）
            val ret = installMethod.invoke(service, apk.absolutePath, true, packageName)
            if (ret is Boolean && !ret) {
                Log.w(TAG, "$METHOD_INSTALL 返回 false")
                Outcome(false, "系统服务拒绝了安装请求")
            } else {
                Log.i(TAG, "$METHOD_INSTALL 已提交：$apkPath -> $packageName")
                Outcome(true, "已通过官方接口提交安装")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "$METHOD_INSTALL 调用异常：${describe(t)}")
            Outcome(false, "安装调用失败：${describe(t)}")
        }
    }

    // ---------------- 内部实现 ----------------

    private fun serviceOf(context: Context): Any? {
        return try {
            context.getSystemService(SERVICE_NAME)
        } catch (t: Throwable) {
            Log.w(TAG, "获取 $SERVICE_NAME 失败：${describe(t)}")
            null
        }
    }

    /**
     * 沿继承链查找方法；首次找不到时会尝试解除隐藏 API 限制再查一次。
     */
    private fun findMethod(clazz: Class<*>, name: String, vararg params: Class<*>): Method? {
        findInHierarchy(clazz, name, params)?.let { return it }
        if (unlockHiddenApi()) {
            findInHierarchy(clazz, name, params)?.let { return it }
        }
        // 兜底：按方法名 + 参数数量 + 参数类型名匹配（避免签名细节差异）
        return findLoose(clazz, name, params)
    }

    private fun findInHierarchy(clazz: Class<*>, name: String, params: Array<out Class<*>>): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                val method = current.getDeclaredMethod(name, *params)
                method.isAccessible = true
                return method
            } catch (e: NoSuchMethodException) {
                // 隐藏 API 限制或本层无此方法，继续向父类查找
            } catch (t: Throwable) {
                Log.w(TAG, "反射 $name 失败：${describe(t)}")
            }
            current = current.superclass
        }
        return null
    }

    private fun findLoose(clazz: Class<*>, name: String, params: Array<out Class<*>>): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            val declared = try {
                current.declaredMethods
            } catch (t: Throwable) {
                emptyArray<Method>()
            }
            val matched = declared.firstOrNull { method ->
                method.name == name &&
                    method.parameterTypes.size == params.size &&
                    method.parameterTypes.indices.all { i ->
                        normalize(method.parameterTypes[i]) == normalize(params[i])
                    }
            }
            if (matched != null) {
                matched.isAccessible = true
                return matched
            }
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

    /**
     * 解除 Android 9+ 对隐藏 API 的反射限制（等价于 FreeReflection 思路）：
     * 通过 java.lang.Class 自身的公开方法拿到 VMRuntime，再设置
     * setHiddenApiExemptions(["L"]) 豁免全部 L 开头（即所有 Java/Kotlin）成员。
     */
    @Suppress("PrivateApi", "DiscouragedPrivateApi")
    private fun unlockHiddenApi(): Boolean {
        if (hiddenApiUnlocked) return true
        return try {
            val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod", String::class.java, Array<Class<*>>::class.java
            )
            val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            // 第二个参数传 null 表示无参方法
            val getRuntime = getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null) as Method
            val setExemptions = getDeclaredMethod.invoke(
                vmRuntimeClass, "setHiddenApiExemptions", arrayOf<Class<*>>(Array<String>::class.java)
            ) as Method
            getRuntime.isAccessible = true
            setExemptions.isAccessible = true
            val runtime = getRuntime.invoke(null)
            setExemptions.invoke(runtime, *arrayOf<Any>(arrayOf("L")))
            hiddenApiUnlocked = true
            Log.i(TAG, "已解除隐藏 API 反射限制")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "解除隐藏 API 限制失败：${describe(t)}")
            false
        }
    }

    /** 拆解 InvocationTargetException，输出真实原因 */
    private fun describe(t: Throwable): String {
        val real = if (t is InvocationTargetException) (t.targetException ?: t) else t
        val message = real.message
        return if (message.isNullOrBlank()) real.javaClass.simpleName
        else "${real.javaClass.simpleName}: $message"
    }
}
