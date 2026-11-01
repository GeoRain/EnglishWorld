package com.englishworld

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.util.Log
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 隐藏 API 反射绕过（文档「三级降级」中的 VMRuntime 方案）。
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
 * ZybOS 设备官方安装接口封装，调用顺序严格对齐《综合报告》Birchua 授权器流程：
 *
 *     ① Hidden API 绕过（VMRuntime.setHiddenApiExemptions）
 *     ② context.getSystemService("zybos_manager") 取 ZybosSysApiManager 实例
 *     ③ 解析 APK 真实路径（FUSE 路径修正 /data/media/0/ → /storage/emulated/0/；
 *        系统服务读不到的路径兜底复制到公共目录，文件名加时间戳防旧包覆盖）
 *     ④ setInstallPackageDisallow(false)   ← 解除 PadMS 安装拦截，紧接安装调用
 *     ⑤ silentInstallApk(apkPath, true, packageName)   ← 静默安装
 *     ⑥ 等待验证（最多 30 秒，每 5 秒检查一次目标包是否完成安装）
 *     ⑦ 清理临时文件（如果复制过的话）
 *
 * 关键约束（来自实测）：
 * - silentInstallApk 由 UID 1000 系统服务在另一个进程读文件：
 *   /proc/fd 与应用私有目录都读不到，公共存储目录（/storage/emulated/0/ 下）可读；
 * - setInstallPackageDisallow(false) 后 PadMS 云控会重置拦截，必须立刻调用安装；
 * - silentInstallApk 是耗时阻塞操作，只能在后台线程调用；
 * - 临时文件名必须带时间戳：公共目录存在同名旧 APK 时会装到旧包。
 *
 * 任何一步失败都不会崩溃，只返回失败原因，由调用方决定是否回退到系统安装器。
 */
object ZybosInstaller {

    /** 定制系统服务名 */
    const val SERVICE_NAME = "zybos_manager"

    /** 系统定制管理类全名 */
    const val MANAGER_CLASS = "android.os.ZybosSysApiManager"

    /** 公共暂存目录：系统服务（UID 1000）实测可读；Download 目录反而读不到 */
    private const val PUBLIC_STAGE_DIR = "/storage/emulated/0/birchua_误删"

    /** FUSE 内部路径前缀 → 用户空间路径前缀 */
    private const val FUSE_PREFIX = "/data/media/0/"
    private const val STORAGE_PREFIX = "/storage/emulated/0/"

    /** 安装验证：最多等待时长与轮询间隔（文档：最多 30 秒，每 5 秒检查一次） */
    private const val VERIFY_TIMEOUT_MS = 30_000L
    private const val VERIFY_INTERVAL_MS = 5_000L

    private const val METHOD_DISALLOW = "setInstallPackageDisallow"
    private const val METHOD_INSTALL = "silentInstallApk"

    private const val TAG = "ZybosInstaller"

    /** 安装结果：success=系统服务已接收安装请求；verified=30 秒内确认目标包已完成安装 */
    data class Outcome(
        val success: Boolean,
        val message: String,
        val verified: Boolean = false
    )

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
     * 通过官方接口静默安装指定 APK（含路径解析、安装验证与临时文件清理）。
     *
     * @param apkPath     待安装 APK 的绝对路径（可以是应用私有目录，内部会自动挪到公共目录）
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

        // 2. 解析 APK 真实路径：系统服务读不到的位置自动复制到公共目录（时间戳文件名）
        val staged = resolveServiceReadablePath(apk)
        val installPath = staged.file.absolutePath
        if (staged.file.absolutePath != apk.absolutePath) {
            Log.i(TAG, "安装包已暂存到公共目录：$installPath")
        }

        try {
            // 3. 绕过 Hidden API 限制
            val bypass = RestrictionBypass()
            val zybosCls = Class.forName(MANAGER_CLASS)

            // 记录安装前的版本状态，供安装验证比对
            val beforeInfo = packageInfoOf(context, packageName)
            val requestTime = System.currentTimeMillis()

            // 4. 解除安装封禁 —— PadMS 云控会重置拦截，必须紧接安装调用，不做其它任何事
            try {
                val disallowInstallMethod =
                    bypass.getDeclaredMethod(zybosCls, METHOD_DISALLOW, Boolean::class.java)
                disallowInstallMethod.invoke(zybosService, false)
                Log.i(TAG, "$METHOD_DISALLOW(false) 执行成功，已解除安装封禁")
            } catch (t: Throwable) {
                // 解除封禁失败不阻断安装，仅记录日志
                Log.w(TAG, "$METHOD_DISALLOW 调用失败：${describe(t)}")
            }

            // 5. 执行静默安装
            val installMethod = bypass.getDeclaredMethod(
                zybosCls, METHOD_INSTALL,
                String::class.java, Boolean::class.java, String::class.java
            )
            val result = invokeInstall(installMethod, zybosService, installPath, true, packageName)
            if (result is Boolean && !result) {
                Log.w(TAG, "$METHOD_INSTALL 返回 false")
                return Outcome(false, "系统服务拒绝了安装请求")
            }
            Log.i(TAG, "$METHOD_INSTALL 已提交：$installPath -> $packageName")

            // 6. 等待验证（最多 30 秒，每 5 秒检查一次）
            val verified = awaitInstallVerified(context, packageName, beforeInfo, requestTime)
            return if (verified) {
                Outcome(true, "已通过官方接口提交安装并确认完成", verified = true)
            } else {
                Outcome(true, "已通过官方接口提交安装（${VERIFY_TIMEOUT_MS / 1000} 秒内未确认完成）")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "$METHOD_INSTALL 调用失败：${describe(t)}")
            return Outcome(false, "安装调用失败：${describe(t)}")
        } finally {
            // 7. 清理临时文件（只有复制过才存在）
            staged.cleanup()
        }
    }

    // ---------------- APK 路径解析（文档 3.2 节） ----------------

    private class StagedFile(val file: File, private val tempCopy: File?) {
        fun cleanup() {
            tempCopy?.let { file ->
                try {
                    if (file.exists() && file.delete()) {
                        Log.i(TAG, "已清理暂存安装包：${file.absolutePath}")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "清理暂存安装包失败：${t.message}")
                }
            }
        }
    }

    /**
     * 路径解析优先级：
     * 1. FUSE 路径修正：/data/media/0/ → /storage/emulated/0/，修正后文件存在即直接使用；
     * 2. 原路径系统服务可读（公共存储目录下）则直接使用；
     * 3. 兜底复制到公共目录 /storage/emulated/0/birchua_误删/，文件名 apk_<时间戳>.apk。
     */
    private fun resolveServiceReadablePath(apk: File): StagedFile {
        // 1. FUSE 路径修正
        if (apk.absolutePath.startsWith(FUSE_PREFIX)) {
            val converted = File(STORAGE_PREFIX + apk.absolutePath.removePrefix(FUSE_PREFIX))
            if (converted.isFile && converted.length() > 0L) {
                return StagedFile(converted, null)
            }
        }

        // 2. 公共存储目录下的路径可直接使用（/storage/emulated/0/xxx，且不是应用专属目录）
        val path = apk.absolutePath
        if (path.startsWith(STORAGE_PREFIX) && !path.contains("/Android/data/")) {
            return StagedFile(apk, null)
        }

        // 3. 兜底：复制到公共目录，时间戳文件名防同名旧包覆盖
        val dir = File(PUBLIC_STAGE_DIR)
        return try {
            if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("无法创建公共暂存目录")
            val target = File(dir, "apk_${System.currentTimeMillis()}.apk")
            if (target.exists()) target.delete()
            apk.copyTo(target, overwrite = true)
            if (target.length() <= 0L) throw IllegalStateException("暂存复制结果为空")
            StagedFile(target, target)
        } catch (t: Throwable) {
            Log.w(TAG, "复制到公共暂存目录失败，退回原路径：${describe(t)}")
            StagedFile(apk, null)
        }
    }

    // ---------------- 安装验证（文档 ⑥） ----------------

    /**
     * 轮询目标包安装状态：出现（原来没有）或 lastUpdateTime 晚于安装请求时间即视为完成。
     * 最多等 [VERIFY_TIMEOUT_MS]，每 [VERIFY_INTERVAL_MS] 检查一次。
     */
    private fun awaitInstallVerified(
        context: Context,
        packageName: String,
        before: PackageInfo?,
        requestTime: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS
        while (System.currentTimeMillis() <= deadline) {
            try {
                Thread.sleep(VERIFY_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            val now = packageInfoOf(context, packageName) ?: continue
            val updated = now.lastUpdateTime >= requestTime
            val newlyInstalled = before == null
            if (updated || newlyInstalled) {
                Log.i(TAG, "安装验证通过：$packageName 已更新（lastUpdateTime=$updated, 新装=$newlyInstalled）")
                return true
            }
        }
        Log.w(TAG, "${VERIFY_TIMEOUT_MS / 1000} 秒内未确认 $packageName 完成安装")
        return false
    }

    private fun packageInfoOf(context: Context, packageName: String): PackageInfo? = try {
        context.packageManager.getPackageInfo(packageName, 0)
    } catch (t: Throwable) {
        null
    }

    // ---------------- 反射调用 ----------------

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
