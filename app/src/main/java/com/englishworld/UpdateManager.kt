package com.englishworld

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 最新版本 APK 的下载与校验。
 *
 * 下载源支持多个候选：先读用户自定义地址（开发者页可改），再依次尝试内置地址。
 * 下载完成后按安装目标的类型分流校验：
 * - 与本应用同包名 → 阻止降级 + 签名必须一致（否则覆盖安装必被系统拒绝）；
 * - 其它包名（官方地址分发的是 JC Code V3 这类独立应用）→ 只做「已安装同包名版本不得降级」
 *   与可解析性校验，由调用方提示用户确认后安装。
 */
class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"

        /**
         * 内置下载源，按顺序尝试。
         *
         * 第一源是仓库 Release 上的稳定附件名（latest/download/JCCodeV3.apk）：
         * 以后发新版本只要在新 Release 里挂同名附件，客户端无需改代码即可拿到最新包；
         * 第二源是官网当前分发地址，作为兜底。
         */
        private val DEFAULT_SOURCES = listOf(
            "https://github.com/GeoRain/EnglishWorld/releases/latest/download/JCCodeV3.apk",
            "https://app.aibangos.xyz/therr.apk"
        )

        /** 自定义下载源（开发者页可改），为空则用内置源 */
        private const val PREFS = "update_prefs"
        private const val KEY_URL = "update_apk_url"

        private const val CONNECT_TIMEOUT = 15_000
        private const val READ_TIMEOUT = 30_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_REDIRECTS = 5
        private const val PROGRESS_INTERVAL = 120L

        fun customSource(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_URL, "").orEmpty().trim()

        fun saveCustomSource(context: Context, url: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_URL, url.trim())
                .apply()
        }

        /** 实际尝试的下载源：自定义优先，其次内置 */
        fun sources(context: Context): List<String> {
            val custom = customSource(context)
            return if (custom.isEmpty()) DEFAULT_SOURCES else listOf(custom) + DEFAULT_SOURCES
        }
    }

    /** 下载回调，均发生在后台线程，调用方需自行切回主线程 */
    interface DownloadCallback {
        fun onProgress(downloaded: Long, total: Long)

        fun onSuccess(apk: File, verification: VerifyResult)
        fun onFailure(message: String)
    }

    /** 校验结果 */
    sealed class VerifyResult {
        /** 本应用的新版本 */
        data class SelfUpdate(val versionName: String, val versionCode: Long) : VerifyResult()

        /** 其它应用（官方地址分发的独立 APK），需用户确认 */
        data class External(
            val packageName: String,
            val versionName: String,
            val versionCode: Long,
            val installedVersionCode: Long
        ) : VerifyResult()

        /** 签名与本机同名应用不一致，覆盖安装会被系统拒绝 */
        data class SignatureMismatch(val reason: String) : VerifyResult()

        /** 不可用（损坏 / 无法解析 / 降级） */
        data class Rejected(val reason: String) : VerifyResult()
    }

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /** 依次尝试各下载源，第一个成功的即返回 */
    fun downloadLatest(callback: DownloadCallback) {
        cancelled = false
        val candidates = sources(context)
        var lastError = "没有任何可用的下载地址"

        for (source in candidates) {
            if (cancelled) {
                callback.onFailure("已取消")
                return
            }
            val apk = try {
                downloadFrom(source, callback)
            } catch (e: Exception) {
                if (cancelled) {
                    callback.onFailure("已取消")
                    return
                }
                lastError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "下载源失败 $source：$lastError")
                null
            } ?: continue

            val result = verify(apk)
            when (result) {
                is VerifyResult.Rejected -> {
                    apk.delete()
                    lastError = result.reason
                    Log.w(TAG, "下载包被拒绝（$source）：${result.reason}")
                }

                else -> {
                    callback.onSuccess(apk, result)
                    return
                }
            }
        }

        callback.onFailure(lastError)
    }

    /** 单个源的下载；失败直接抛异常，由上层切换下一个源 */
    private fun downloadFrom(source: String, callback: DownloadCallback): File? {
        val dir = File(context.cacheDir, "apk").apply { mkdirs() }
        val target = File(dir, "latest.apk")
        val temp = File(dir, "latest.apk.part")
        var connection: HttpURLConnection? = null

        try {
            if (temp.exists()) temp.delete()

            val conn = openFollowRedirects(source)
                ?: throw IllegalStateException("下载地址重定向异常")
            connection = conn
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = 0L
                    var lastReport = 0L
                    while (true) {
                        if (cancelled) throw InterruptedException("已取消")
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= PROGRESS_INTERVAL) {
                            lastReport = now
                            callback.onProgress(downloaded, total)
                        }
                    }
                    callback.onProgress(downloaded, total)
                    if (downloaded <= 0L) throw IllegalStateException("下载内容为空")
                    if (total > 0L && downloaded != total) {
                        throw IllegalStateException("下载不完整（$downloaded/$total）")
                    }
                }
            }

            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            return target
        } catch (e: InterruptedException) {
            throw e
        } finally {
            connection?.disconnect()
            if (temp.exists()) temp.delete()
        }
    }

    /** 手动跟随重定向（跨协议重定向时 HttpURLConnection 不会自动处理） */
    private fun openFollowRedirects(source: String): HttpURLConnection? {
        var url = URL(source)
        var redirects = 0
        while (redirects <= MAX_REDIRECTS) {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", "EnglishWorld/${currentVersionName()}")
                setRequestProperty("Accept", "application/vnd.android.package-archive,*/*")
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) return null
                url = URL(url, location)
                redirects++
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                throw IllegalStateException("服务器返回 HTTP $code")
            }
            return connection
        }
        return null
    }

    // ---------------- 校验 ----------------

    fun verify(apk: File): VerifyResult {
        if (!apk.isFile || apk.length() < 1024L) {
            return VerifyResult.Rejected("安装包无效（文件过小），可能下载不完整")
        }
        if (!looksLikeZip(apk)) {
            return VerifyResult.Rejected("下载到的不是 APK（可能是网页或错误页）")
        }

        val flags = packageFlags()
        val archive = try {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        } catch (e: Exception) {
            Log.w(TAG, "解析安装包失败", e)
            null
        } ?: return VerifyResult.Rejected("安装包无法解析，可能下载不完整")

        val newPackage = archive.packageName ?: return VerifyResult.Rejected("安装包缺少包名")
        val newCode = versionCodeOf(archive)
        val newName = archive.versionName ?: "-"

        // 不是本应用：走「外部应用安装」校验（官方地址可能分发其它产品）
        if (newPackage != context.packageName) {
            val installed = installedInfo(newPackage)
            val installedCode = installed?.let { versionCodeOf(it) } ?: 0L
            if (installedCode > 0L && newCode < installedCode) {
                return VerifyResult.Rejected(
                    "服务器上的 $newPackage（$newCode）低于本机已安装的 $installedCode，已阻止降级"
                )
            }
            // 已装同名应用时签名必须一致，否则覆盖安装必被系统拒绝
            if (installed != null) {
                val oldDigest = signatureDigest(installed)
                val newDigest = signatureDigest(archive)
                if (oldDigest != null && newDigest != null && !oldDigest.contentEquals(newDigest)) {
                    return VerifyResult.Rejected(
                        "下载的 $newPackage 与本机已安装版本签名不一致，无法覆盖安装"
                    )
                }
            }
            return VerifyResult.External(newPackage, newName, newCode, installedCode)
        }

        // 本应用：阻止降级 + 签名一致
        val installed = installedInfo(context.packageName)
            ?: return VerifyResult.Rejected("无法读取本机版本信息")

        val installedCode = versionCodeOf(installed)
        if (newCode < installedCode) {
            return VerifyResult.Rejected(
                "服务器上的版本（$newCode）低于当前版本（$installedCode），已阻止降级安装"
            )
        }

        val installedDigest = signatureDigest(installed)
        val archiveDigest = signatureDigest(archive)
        if (installedDigest != null && archiveDigest != null && !installedDigest.contentEquals(archiveDigest)) {
            return VerifyResult.SignatureMismatch("安装包签名与本机版本不一致，系统会拒绝覆盖安装")
        }
        return VerifyResult.SelfUpdate(newName, newCode)
    }

    /** APK 文件头校验：ZIP 魔数 PK\003\004 */
    private fun looksLikeZip(file: File): Boolean {
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                if (input.read(header) != 4) return false
                header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                    header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun installedInfo(packageName: String): PackageInfo? = try {
        context.packageManager.getPackageInfo(packageName, packageFlags())
    } catch (e: Exception) {
        null
    }

    private fun packageFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }

    private fun versionCodeOf(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }

    private fun signatureDigest(info: PackageInfo): ByteArray? {
        return try {
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
            signature?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()) }
        } catch (e: Exception) {
            Log.w(TAG, "读取签名失败", e)
            null
        }
    }

    private fun currentVersionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }
}
