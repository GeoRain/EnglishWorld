package com.englishworld

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File

/**
 * 安装通道编排：先试设备官方接口（静默），不可用再交系统安装器（用户确认）。
 *
 * 对齐 JC Code V3 3.2.6 的行为：
 * 1. 安装前先绑定探测，拿到「可用 / 被签名校验封禁 / 设备无此接口」三种明确结论；
 * 2. 绑定可用才走静默安装，失败会给出具体原因而不是笼统的“安装失败”；
 * 3. 不具备 shell / device owner 时，不再假装有 ADB 通道，直接引导系统安装器。
 */
object ApkInstaller {

    enum class Channel(val label: String) {
        ZYBOS("设备官方接口"),
        SYSTEM("系统安装器")
    }

    data class Result(
        val success: Boolean,
        val channel: Channel?,
        val verified: Boolean,
        /** true 表示需要用户在弹出的系统安装页里点确认 */
        val needsUserConfirm: Boolean,
        val message: String
    )

    interface Callback {
        /** 后台线程回调，调用方需自行切回主线程 */
        fun onStage(stage: String)

        /** 后台线程回调 */
        fun onResult(result: Result)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 探测设备官方安装接口状态（可在任意线程调用，会做反射操作） */
    fun probe(context: Context): ZybosBridge.Status = ZybosBridge(context.applicationContext).connect()

    /** 后台线程执行安装，回调抛回主线程 */
    fun installAsync(
        context: Context,
        apk: File,
        targetPackage: String,
        callback: Callback
    ) {
        val appContext = context.applicationContext
        Thread {
            val result = installBlocking(appContext, apk, targetPackage) { stage ->
                mainHandler.post { callback.onStage(stage) }
            }
            mainHandler.post { callback.onResult(result) }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 同步安装（阻塞，禁止在主线程调用）。
     *
     * 官方接口可用 → 静默安装并按结果返回；
     * 否则返回 needsUserConfirm=true，由调用方弹出系统安装器完成。
     */
    fun installBlocking(
        context: Context,
        apk: File,
        targetPackage: String,
        onStage: ((String) -> Unit)? = null
    ): Result {
        if (!apk.isFile || apk.length() <= 0L) {
            return Result(false, null, false, false, "安装包不存在或为空")
        }

        onStage?.invoke("正在检测设备官方安装接口…")
        val bridge = ZybosBridge(context)
        val status = bridge.connect()

        if (status is ZybosBridge.Status.Ready) {
            onStage?.invoke("正在通过${Channel.ZYBOS.label}安装…")
            val outcome = bridge.install(apk, targetPackage)
            return if (outcome.submitted) {
                Result(true, Channel.ZYBOS, outcome.verified, false, outcome.message)
            } else {
                Result(false, Channel.ZYBOS, false, true, outcome.message)
            }
        }

        // 被系统签名校验封禁 / 设备没有该接口：交给系统安装器，由用户确认
        return Result(false, null, false, true, status.message)
    }

    /** 是否已获准安装未知来源应用 */
    fun canRequestInstall(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
    }

    /** 安装前预检结论 */
    sealed class Preflight {
        object Ready : Preflight()
        data class Downgrade(val installed: Long, val incoming: Long) : Preflight()
        data class SignatureMismatch(val packageName: String) : Preflight()
        data class Invalid(val reason: String) : Preflight()
    }

    /**
     * 安装前预检：包是否可解析、版本是否降级、已装同名应用的签名是否一致。
     *
     * 现实场景：官网分发的 3.2.6 与 3.1.0 用了不同证书，覆盖安装必然
     * INSTALL_FAILED_UPDATE_INCOMPATIBLE，提前拦下比装完再报错更清楚。
     */
    fun preflight(context: Context, apk: File, targetPackage: String): Preflight {
        if (!apk.isFile || apk.length() < 1024L) {
            return Preflight.Invalid("安装包不存在或文件过小")
        }
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        val archive: android.content.pm.PackageInfo? = try {
            pm.getPackageArchiveInfo(apk.absolutePath, flags)
        } catch (t: Throwable) {
            null
        }
        if (archive == null) {
            return Preflight.Invalid("安装包无法解析，文件可能已损坏")
        }

        val installed: android.content.pm.PackageInfo? = try {
            pm.getPackageInfo(targetPackage, flags)
        } catch (t: Throwable) {
            null
        }
        if (installed == null) {
            // 本机没装这个包，直接放行
            return Preflight.Ready
        }

        val incoming = versionCodeOf(archive)
        val current = versionCodeOf(installed)
        if (incoming < current) return Preflight.Downgrade(current, incoming)

        val oldDigest = signatureDigest(installed)
        val newDigest = signatureDigest(archive)
        if (oldDigest != null && newDigest != null && !oldDigest.contentEquals(newDigest)) {
            return Preflight.SignatureMismatch(targetPackage)
        }
        return Preflight.Ready
    }

    private fun versionCodeOf(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private fun signatureDigest(info: android.content.pm.PackageInfo): ByteArray? = try {
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo
            if (signing == null) {
                null
            } else {
                val certificates = if (signing.hasMultipleSigners()) {
                    signing.apkContentsSigners
                } else {
                    signing.signingCertificateHistory
                }
                certificates?.firstOrNull()
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()
        }
        signature?.let { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()) }
    } catch (t: Throwable) {
        null
    }

    /** 构造系统安装器 Intent（FileProvider 授权，兼容 Android 7+） */
    fun buildSystemInstallIntent(context: Context, apk: File): Intent? {
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", apk
            )
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** 直接拉起系统安装器，返回是否成功启动 */
    fun launchSystemInstaller(context: Context, apk: File): Boolean {
        val intent = buildSystemInstallIntent(context, apk) ?: return false
        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
