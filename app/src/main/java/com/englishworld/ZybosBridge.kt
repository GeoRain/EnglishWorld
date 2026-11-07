package com.englishworld

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.util.Log
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 隐藏 API 反射限制绕过 + 方法查找。
 *
 * Android 9 起非 SDK 成员的反射会被系统策略拦截（NoSuchMethodException）。
 * 这里先用 dalvik.system.VMRuntime#setHiddenApiExemptions(["L"]) 解除限制，
 * 再按名字 + 签名取方法：精确签名 → 装箱换原始类型 → 按名字与参数个数松匹配。
 */
object HiddenApi {

    private const val TAG = "HiddenApi"

    @Volatile
    private var unlocked = false

    /** 解除隐藏 API 限制；已成功过则直接返回 true */
    fun unlock(): Boolean {
        if (unlocked) return true
        try {
            val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java
            )
            val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime") as Method
            val setExemptions = getDeclaredMethod.invoke(
                vmRuntimeClass, "setHiddenApiExemptions", arrayOf<Class<*>>(arrayOf<String>()::class.java)
            ) as Method
            getRuntime.isAccessible = true
            setExemptions.isAccessible = true
            val runtime = getRuntime.invoke(null)
            setExemptions.invoke(runtime, *arrayOf<Any>(arrayOf("L")))
            unlocked = true
            Log.i(TAG, "已解除隐藏 API 反射限制")
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "解除隐藏 API 限制失败：${describe(t)}")
            return false
        }
    }

    /** 开发自检：取一个必然被隐藏 API 策略拦截的方法，验证绕过是否真的生效 */
    fun selfTest(): String {
        return try {
            val method = findMethod(
                Class.forName("android.app.ActivityManager"), "forceStopPackage", String::class.java
            )
            if (method == null) "不可用（未取到 forceStopPackage）" else "可用（已取到 ${method.name}）"
        } catch (t: Throwable) {
            "不可用：${describe(t)}"
        }
    }

    /**
     * 取声明方法：先精确签名，再试原始类型，最后沿继承链按名字 + 参数个数兜底。
     * 找不到返回 null（调用方自行决定降级策略），不抛异常。
     */
    fun findMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        unlock()

        try {
            return clazz.getDeclaredMethod(name, *paramTypes).also { it.isAccessible = true }
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "精确签名未命中 ${clazz.name}#$name(${paramTypes.joinToString(",") { it.simpleName }})")
        }

        val primitives = paramTypes.map { toPrimitive(it) }.toTypedArray()
        if (!(primitives contentEquals paramTypes)) {
            try {
                return clazz.getDeclaredMethod(name, *primitives).also { it.isAccessible = true }
            } catch (e: NoSuchMethodException) {
                // 继续降级
            }
        }

        return findLoose(clazz, name, paramTypes) ?: findLoose(clazz, name, primitives)
    }

    private fun findLoose(clazz: Class<*>, name: String, paramTypes: Array<out Class<*>>): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            val declared = try {
                current.declaredMethods
            } catch (t: Throwable) {
                emptyArray<Method>()
            }
            // 1. 名字 + 个数 + 参数类型全匹配
            declared.firstOrNull { method ->
                method.name == name &&
                    method.parameterTypes.size == paramTypes.size &&
                    method.parameterTypes.indices.all { i ->
                        normalize(method.parameterTypes[i]) == normalize(paramTypes[i])
                    }
            }?.let { return it.also { m -> m.isAccessible = true } }

            // 2. 名字 + 参数个数匹配（参数类型可能被改写成包装类）
            declared.firstOrNull { method ->
                method.name == name && method.parameterTypes.size == paramTypes.size
            }?.let { return it.also { m -> m.isAccessible = true } }

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

    /** 拆解 InvocationTargetException，输出真实原因 */
    fun describe(t: Throwable): String {
        val real = if (t is InvocationTargetException) (t.targetException ?: t) else t
        return if (real.message.isNullOrBlank()) real.javaClass.simpleName
        else "${real.javaClass.simpleName}: ${real.message}"
    }
}

/**
 * 设备官方安装接口（zybos_manager）的绑定、探测与调用。
 *
 * 结构对齐 JC Code V3 3.2.6 的 MainActivity：先「绑定」拿到服务与类，
 * 扫描方法可用性，再用一个只读方法探测系统签名校验是否放行，
 * 最后才 setInstallPackageDisallow(false) → silentInstallApk(path, true, pkg)。
 *
 * 三种状态必须区分清楚，不能把「被系统封禁」误报成「调用失败」：
 * - Ready   ：服务/类/方法都在，且探测调用未被拦截；
 * - Blocked ：服务在，但调用抛 SecurityException（高版本 enforceCallerIsSystem）；
 * - Missing ：服务或类或方法根本不存在（非 ZybOS 设备）。
 *
 * 与 3.2.6 的差异：它还有「无线调试 ADB」「设备所有者 OW」两条通道，
 * 那两条需要 shell(2000) / device owner 权限，普通应用拿不到，故本项目不实现，
 * 由调用方回退系统安装器（用户手动确认）。
 */
class ZybosBridge(private val context: Context) {

    companion object {
        private const val TAG = "ZybosBridge"

        const val SERVICE_NAME = "zybos_manager"
        const val MANAGER_CLASS = "android.os.ZybosSysApiManager"

        private const val M_DISALLOW = "setInstallPackageDisallow"
        private const val M_INSTALL = "silentInstallApk"
        private const val M_UNINSTALL = "silentUninstallApk"
        private const val M_PACKAGE_LIST = "setPackageListDisallow"
        private const val M_PROBE_DISALLOW = "isDisallowInstallPackage"
        private const val M_PROBE_SILENT = "isSilentInstallDisallow"

        /** 探测用的占位包名，不会真正安装 */
        private const val PROBE_PACKAGE = "com.englishworld.probe"

        /** 安装验证：最多等待 30 秒，每 2 秒查一次（3.2.6 为 5 秒后单次确认，这里放宽以免误判） */
        private const val VERIFY_TIMEOUT_MS = 30_000L
        private const val VERIFY_INTERVAL_MS = 2_000L

        /** FUSE 内部路径 → 用户空间路径 */
        private const val FUSE_PREFIX = "/data/media/0/"
        private const val STORAGE_PREFIX = "/storage/emulated/0/"
    }

    /** 绑定结果 */
    sealed class Status {
        abstract val message: String

        /** 接口可用 */
        data class Ready(val capability: Capability, override val message: String) : Status()

        /** 服务存在但被系统签名校验拦截 */
        data class Blocked(override val message: String) : Status()

        /** 设备没有这个接口 */
        data class Missing(override val message: String) : Status()
    }

    data class Capability(
        val install: Boolean,
        val uninstall: Boolean,
        val packageList: Boolean
    )

    /** 一次安装调用的结果：submitted=系统服务已接收；verified=30 秒内确认真的装上了 */
    data class Outcome(val submitted: Boolean, val verified: Boolean, val message: String)

    /** 绑定并探测：每次安装前都重新探测，因为 PadMS 云控会重置拦截状态 */
    fun connect(): Status {
        if (!HiddenApi.unlock()) {
            return Status.Missing("隐藏 API 绕过失败，系统禁止反射隐藏成员")
        }

        val service: Any? = try {
            context.getSystemService(SERVICE_NAME)
        } catch (t: Throwable) {
            Log.w(TAG, "获取 $SERVICE_NAME 失败：${HiddenApi.describe(t)}")
            null
        }
        if (service == null) {
            return Status.Missing("本机没有 $SERVICE_NAME 服务（非 ZybOS 设备）")
        }

        val clazz: Class<*>? = try {
            Class.forName(MANAGER_CLASS)
        } catch (t: Throwable) {
            Log.w(TAG, "加载 $MANAGER_CLASS 失败：${HiddenApi.describe(t)}")
            null
        }
        if (clazz == null) {
            return Status.Missing("系统缺少 $MANAGER_CLASS 类")
        }

        val install = HiddenApi.findMethod(
            clazz, M_INSTALL, String::class.java, java.lang.Boolean.TYPE, String::class.java
        ) != null
        val uninstall = HiddenApi.findMethod(
            clazz, M_UNINSTALL, String::class.java, java.lang.Boolean.TYPE
        ) != null
        val packageList = HiddenApi.findMethod(
            clazz, M_PACKAGE_LIST, java.lang.Boolean.TYPE
        ) != null

        if (!install) {
            return Status.Missing("$MANAGER_CLASS 上找不到 $M_INSTALL 方法")
        }

        // 签名校验探测：调一个只读方法，被拦截会抛 SecurityException
        val blocked = probeEnforcement(clazz, service)
        if (blocked != null) return Status.Blocked(blocked)

        return Status.Ready(
            Capability(install = install, uninstall = uninstall, packageList = packageList),
            "设备官方接口可用（可安装：${if (install) "是" else "否"}，可卸载：${if (uninstall) "是" else "否"}）"
        )
    }

    /**
     * 静默安装：解除安装封禁后立刻调用安装，最后轮询验证。
     *
     * 关键约束（实测）：
     * - 系统服务（UID 1000）读不到应用私有目录，安装包会先挪到它能读的位置；
     * - setInstallPackageDisallow(false) 之后不能做别的事，PadMS 云控会重新拦截；
     * - silentInstallApk 是阻塞调用，只能在后台线程执行。
     */
    fun install(apk: File, packageName: String): Outcome {
        if (!apk.isFile || apk.length() <= 0L) {
            return Outcome(false, false, "安装包不存在或为空")
        }

        val status = connect()
        if (status !is Status.Ready) {
            return Outcome(false, false, status.message)
        }

        val service = readService() ?: return Outcome(false, false, "无法取得 $SERVICE_NAME 服务实例")
        val clazz = readClass() ?: return Outcome(false, false, "无法加载 $MANAGER_CLASS 类")

        val staged = stageForService(apk)
        val installPath = staged.file.absolutePath
        if (installPath != apk.absolutePath) {
            Log.i(TAG, "安装包已暂存到系统服务可读的位置：$installPath")
        }

        try {
            val before = packageInfoOf(packageName)
            val requestTime = System.currentTimeMillis()

            // ① 解除安装封禁：必须紧接安装调用，中间不做任何其它事
            HiddenApi.findMethod(clazz, M_DISALLOW, java.lang.Boolean.TYPE)?.let { method ->
                try {
                    method.invoke(service, false)
                    Log.i(TAG, "$M_DISALLOW(false) 已执行")
                } catch (t: Throwable) {
                    Log.w(TAG, "$M_DISALLOW 调用失败：${HiddenApi.describe(t)}")
                }
            }

            // ② 静默安装
            val installMethod = HiddenApi.findMethod(
                clazz, M_INSTALL, String::class.java, java.lang.Boolean.TYPE, String::class.java
            ) ?: return Outcome(false, false, "找不到 $M_INSTALL 方法")

            val result = invokeInstall(installMethod, service, installPath, packageName)
            if (result is Boolean && !result) {
                return Outcome(false, false, "系统服务拒绝了安装请求")
            }
            Log.i(TAG, "$M_INSTALL 已提交：$installPath -> $packageName")

            // ③ 验证
            val verified = awaitVerified(packageName, before, requestTime)
            return if (verified) {
                Outcome(true, true, "已通过设备官方接口安装完成")
            } else {
                Outcome(true, false, "安装请求已提交，${VERIFY_TIMEOUT_MS / 1000} 秒内未确认完成")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "安装调用失败：${HiddenApi.describe(t)}")
            return Outcome(false, false, "安装调用失败：${HiddenApi.describe(t)}")
        } finally {
            staged.cleanup()
        }
    }

    /** 静默卸载（3.2.6 同样走 silentUninstallApk），失败返回原因 */
    fun uninstall(packageName: String): Outcome {
        val status = connect()
        if (status !is Status.Ready) return Outcome(false, false, status.message)

        val service = readService() ?: return Outcome(false, false, "无法取得 $SERVICE_NAME 服务实例")
        val clazz = readClass() ?: return Outcome(false, false, "无法加载 $MANAGER_CLASS 类")

        val method = HiddenApi.findMethod(clazz, M_UNINSTALL, String::class.java, java.lang.Boolean.TYPE)
            ?: return Outcome(false, false, "找不到 $M_UNINSTALL 方法")

        return try {
            method.invoke(service, packageName, true)
            Outcome(true, false, "卸载请求已提交")
        } catch (t: Throwable) {
            Outcome(false, false, "卸载失败：${HiddenApi.describe(t)}")
        }
    }

    // ---------------- 内部实现 ----------------

    private fun probeEnforcement(clazz: Class<*>, service: Any): String? {
        val method = HiddenApi.findMethod(clazz, M_PROBE_DISALLOW)
            ?: HiddenApi.findMethod(clazz, M_PROBE_SILENT, String::class.java)
            ?: return null // 没有探针方法：不做结论，按可用处理

        return try {
            if (method.parameterTypes.isEmpty()) {
                method.invoke(service)
            } else {
                method.invoke(service, PROBE_PACKAGE)
            }
            null
        } catch (e: InvocationTargetException) {
            val cause = e.targetException ?: e
            if (cause is SecurityException) {
                Log.w(TAG, "签名校验拦截：${cause.message}")
                "设备官方接口被系统签名校验封禁（enforceCallerIsSystem，仅 system/root/平台签名可调用）"
            } else {
                Log.w(TAG, "探测调用异常：${HiddenApi.describe(cause)}")
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "探测调用异常：${HiddenApi.describe(t)}")
            null
        }
    }

    /**
     * 部分设备 getSystemService 返回的是 Binder 代理，真实类型不是 ZybosSysApiManager，
     * 用 Class.forName 取到的方法 invoke 会抛 IllegalArgumentException，此时退回真实类型。
     */
    private fun invokeInstall(method: Method, service: Any, path: String, packageName: String): Any? {
        return try {
            method.invoke(service, path, true, packageName)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "方法声明类型与服务对象不匹配，改用服务真实类型：${e.message}")
            val actual = HiddenApi.findMethod(
                service.javaClass, M_INSTALL, String::class.java, java.lang.Boolean.TYPE, String::class.java
            ) ?: return null
            actual.invoke(service, path, true, packageName)
        }
    }

    private fun readService(): Any? = try {
        context.getSystemService(SERVICE_NAME)
    } catch (t: Throwable) {
        null
    }

    private fun readClass(): Class<*>? = try {
        Class.forName(MANAGER_CLASS)
    } catch (t: Throwable) {
        null
    }

    private class Staged(val file: File, private val temp: File?) {
        fun cleanup() {
            val target = temp ?: return
            try {
                if (target.exists()) target.delete()
                Log.i(TAG, "已清理暂存安装包：${target.absolutePath}")
            } catch (t: Throwable) {
                Log.w(TAG, "清理暂存安装包失败：${t.message}")
            }
        }
    }

    /**
     * 把安装包挪到系统服务（UID 1000）能读到的位置。
     *
     * 顺序：FUSE 路径修正 → 原路径已在公共存储则直接用 →
     * 应用外部目录（无需存储权限）→ /storage/emulated/0 下的公共目录 → 都不行就交原路径。
     * 文件名带时间戳，避免公共目录里的同名旧包被装上去。
     */
    private fun stageForService(apk: File): Staged {
        if (apk.absolutePath.startsWith(FUSE_PREFIX)) {
            val converted = File(STORAGE_PREFIX + apk.absolutePath.removePrefix(FUSE_PREFIX))
            if (converted.isFile && converted.length() == apk.length()) {
                return Staged(converted, null)
            }
        }

        if (isServiceReadablePath(apk)) return Staged(apk, null)

        val stamp = System.currentTimeMillis()
        val candidates = ArrayList<File>(2)
        context.getExternalFilesDir("install")?.let { candidates.add(File(it, "apk_$stamp.apk")) }
        candidates.add(File("/storage/emulated/0/EnglishWorld_update", "apk_$stamp.apk"))

        for (target in candidates) {
            val copied = try {
                target.parentFile?.mkdirs()
                if (target.exists()) target.delete()
                apk.copyTo(target, overwrite = true)
                if (target.isFile && target.length() == apk.length()) target else null
            } catch (t: Throwable) {
                Log.w(TAG, "暂存到 ${target.absolutePath} 失败：${HiddenApi.describe(t)}")
                null
            }
            if (copied != null) return Staged(copied, copied)
        }

        Log.w(TAG, "没有可写的公共目录，退回原路径（系统服务可能读不到）")
        return Staged(apk, null)
    }

    private fun isServiceReadablePath(file: File): Boolean {
        val path = file.absolutePath
        return path.startsWith(STORAGE_PREFIX) &&
            !path.contains("/Android/data/") &&
            !path.contains("/Android/obb/")
    }

    private fun awaitVerified(packageName: String, before: PackageInfo?, requestTime: Long): Boolean {
        val beforeCode = before?.let { versionCodeOf(it) } ?: 0L
        val deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS
        while (System.currentTimeMillis() <= deadline) {
            if (sleepInterval()) return false
            val now = packageInfoOf(packageName) ?: continue
            if (versionCodeOf(now) > beforeCode) return true
            // 同版本覆盖安装：版本号没变，但 lastUpdateTime 晚于请求时间也算成功
            if (before != null && now.lastUpdateTime >= requestTime) return true
        }
        Log.w(TAG, "${VERIFY_TIMEOUT_MS / 1000} 秒内未确认 $packageName 安装完成")
        return false
    }

    /** @return true 表示等待被中断，调用方应停止轮询 */
    private fun sleepInterval(): Boolean = try {
        Thread.sleep(VERIFY_INTERVAL_MS)
        false
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        true
    }

    private fun packageInfoOf(packageName: String): PackageInfo? = try {
        context.packageManager.getPackageInfo(packageName, 0)
    } catch (t: Throwable) {
        null
    }

    private fun versionCodeOf(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }

    /** 供开发者页展示的一行诊断 */
    fun describe(): String = connect().message
}
