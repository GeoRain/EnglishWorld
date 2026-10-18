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
 * 官方最新版本 APK 的下载与校验。
 *
 * 下载地址固定为官网分发地址，下载完成后依次校验：
 * 1. 文件完整可解析；
 * 2. 包名与本应用一致（不一致时交由用户确认，不静默安装）；
 * 3. 版本号不低于当前版本（阻止降级）；
 * 4. 签名证书与本应用一致（阻止被第三方重签包覆盖）。
 */
class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"

        /** 官方最新版 APK 分发地址 */
        const val LATEST_APK_URL = "https://app.aibangos.xyz/therr.apk"

        private const val CONNECT_TIMEOUT = 15_000
        private const val READ_TIMEOUT = 30_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_REDIRECTS = 5
        private const val PROGRESS_INTERVAL = 120L
    }

    /** 下载回调，均发生在后台线程，调用方需自行切回主线程 */
    interface DownloadCallback {
        fun onProgress(downloaded: Long, total: Long)

        /** 下载完成且通过基础校验；verification 可能为 SignatureMismatch，需提示用户 */
        fun onSuccess(apk: File, verification: VerifyResult)
        fun onFailure(message: String)
    }

    /** 校验结果 */
    sealed class VerifyResult {
        object Passed : VerifyResult()

        /** 安装包不可用（损坏 / 无法解析 / 降级），直接拦截 */
        data class Rejected(val reason: String) : VerifyResult()

        /** 安装包不属于本应用：需用户明确确认后才安装 */
        data class DifferentPackage(val packageName: String, val versionName: String) : VerifyResult()

        /** 与本机版本签名不一致：覆盖安装会被系统拒绝 */
        data class SignatureMismatch(val reason: String) : VerifyResult()
    }

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /** 下载最新版 APK 到 cacheDir/apk/latest.apk，成功后已完成全部校验 */
    fun downloadLatest(callback: DownloadCallback) {
        cancelled = false
        val dir = File(context.cacheDir, "apk").apply { mkdirs() }
        val target = File(dir, "latest.apk")
        val temp = File(dir, "latest.apk.part")
        var connection: HttpURLConnection? = null

        try {
            if (temp.exists()) temp.delete()

            val conn = openFollowRedirects()
            if (conn == null) {
                callback.onFailure("下载地址重定向异常，无法获取安装包")
                return
            }
            connection = conn
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = 0L
                    var lastReport = 0L
                    while (true) {
                        if (cancelled) {
                            callback.onFailure("已取消")
                            return
                        }
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
                }
            }

            if (temp.length() <= 0L) {
                temp.delete()
                callback.onFailure("下载内容为空，请检查网络后重试")
                return
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }

            val result = verify(target)
            if (result is VerifyResult.Rejected) {
                target.delete()
                callback.onFailure(result.reason)
                return
            }
            // Passed 与 SignatureMismatch 均交由调用方处理（后者需额外提示）
            callback.onSuccess(target, result)
        } catch (e: Exception) {
            Log.e(TAG, "下载失败", e)
            if (temp.exists()) temp.delete()
            if (cancelled) callback.onFailure("已取消") else callback.onFailure("下载失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
        }
    }

    /** 手动跟随重定向（跨协议重定向时 HttpURLConnection 不会自动处理） */
    private fun openFollowRedirects(): HttpURLConnection? {
        var url = URL(LATEST_APK_URL)
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
        val flags = packageFlags()
        val archive = try {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        } catch (e: Exception) {
            Log.w(TAG, "解析安装包失败", e)
            null
        } ?: return VerifyResult.Rejected("安装包无法解析，可能下载不完整")

        if (archive.packageName != context.packageName) {
            // 不是本应用的包：交给用户确认（官方分发地址可能指向其它产品）
            return VerifyResult.DifferentPackage(
                archive.packageName ?: "-",
                archive.versionName ?: "-"
            )
        }

        val installed = try {
            context.packageManager.getPackageInfo(context.packageName, flags)
        } catch (e: Exception) {
            Log.w(TAG, "读取本机版本信息失败", e)
            null
        } ?: return VerifyResult.Rejected("无法读取本机版本信息")

        val newCode = versionCodeOf(archive)
        val oldCode = versionCodeOf(installed)
        if (newCode < oldCode) {
            return VerifyResult.Rejected("服务器上的版本（$newCode）低于当前版本（$oldCode），已阻止降级安装")
        }

        val installedDigest = signatureDigest(installed)
        val archiveDigest = signatureDigest(archive)
        if (installedDigest != null && archiveDigest != null && !installedDigest.contentEquals(archiveDigest)) {
            return VerifyResult.SignatureMismatch("安装包签名与本机版本不一致，系统会拒绝覆盖安装")
        }
        return VerifyResult.Passed
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
