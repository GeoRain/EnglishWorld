package com.englishworld

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.englishworld.databinding.FragmentMineBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MineFragment : Fragment() {

    private var _binding: FragmentMineBinding? = null
    private val binding get() = _binding!!

    private lateinit var userData: UserDataManager
    private lateinit var wordDatabase: WordDatabase

    // 隐藏的开发者调试入口：连点版本号触发，无任何提示
    private var versionTapCount = 0
    private var lastVersionTapTime = 0L

    // 最新版本安装：下载器与忙碌标记（避免重复点击并发下载）
    private val mainHandler = Handler(Looper.getMainLooper())
    private var updater: UpdateManager? = null
    private var updateBusy = false

    // 等待“允许安装未知应用”权限授予后自动继续安装的 APK
    private var pendingInstallApk: File? = null

    // 选择 APK 文件的启动器：选中后复制到缓存并拉起系统安装器（覆盖安装本应用）
    private val pickApkLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) handlePickedApk(uri)
        }

    private companion object {
        /** 两次点击版本号的最大间隔，超过则重新计数 */
        const val TAP_WINDOW_MS = 2000L

        /** 解锁「安装我们的最新版本」入口所需的连点次数 */
        const val UNLOCK_TAP_COUNT = 9
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentMineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        userData = UserDataManager(requireContext())
        wordDatabase = WordDatabase(requireContext())
        setupUserInfo()
        setupClickListeners()
        // 已解锁过则直接显示安装入口
        binding.cardUpdate.visibility =
            if (userData.updateEntryUnlocked) View.VISIBLE else View.GONE
    }

    private fun setupUserInfo() {
        binding.textStudyDays.text = "已学习 ${userData.studyDays} 天"
        try {
            val packageInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            binding.textVersion.text = "版本 v${packageInfo.versionName}"
        } catch (e: Exception) {
            binding.textVersion.text = "版本 v1.0.0"
        }
    }

    private fun setupClickListeners() {
        binding.cardFavorite.setOnClickListener {
            val favorites = wordDatabase.getAllWords()
                .filter { it.word in userData.getFavorites() }
            WordDialogs.showWordListDialog(
                requireContext(),
                "我的收藏（${favorites.size}）",
                favorites,
                userData
            ) {
                (activity as? MainActivity)?.navigateToTab(2)
            }
        }

        binding.cardHistory.setOnClickListener {
            val historyWords = userData.getHistory().mapNotNull { word ->
                wordDatabase.findByWord(word)
            }
            WordDialogs.showWordListDialog(
                requireContext(),
                "查询历史（${historyWords.size}）",
                historyWords,
                userData
            ) {
                (activity as? MainActivity)?.navigateToTab(1)
            }
        }

        binding.cardSettings.setOnClickListener {
            showSettingsDialog()
        }

        binding.cardWebsite.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://georain.github.io/EnglishWorld"))
            startActivity(intent)
        }

        binding.cardAbout.setOnClickListener {
            showAboutDialog()
        }

        binding.cardPrivacy.setOnClickListener {
            startActivity(
                Intent(requireContext(), PolicyActivity::class.java)
                    .putExtra(PolicyActivity.EXTRA_IS_PRIVACY, true)
            )
        }

        binding.cardTerms.setOnClickListener {
            startActivity(
                Intent(requireContext(), PolicyActivity::class.java)
                    .putExtra(PolicyActivity.EXTRA_IS_PRIVACY, false)
            )
        }

        // 解锁后的入口：下载官方最新版并通过设备官方接口安装
        binding.cardUpdate.setOnClickListener {
            startInstallLatest()
        }

        // 隐藏入口：连点版本号 9 次解锁「安装我们的最新版本」，第 10 次打开开发者调试信息
        binding.textVersion.setOnClickListener {
            val now = System.currentTimeMillis()
            // 两次点击间隔超过 2 秒则重新计数，避免误触
            versionTapCount = if (now - lastVersionTapTime <= TAP_WINDOW_MS) versionTapCount + 1 else 1
            lastVersionTapTime = now
            if (versionTapCount == UNLOCK_TAP_COUNT) {
                unlockUpdateEntry()
            } else if (versionTapCount > UNLOCK_TAP_COUNT) {
                versionTapCount = 0
                showDeveloperInfoDialog()
            }
        }
    }

    // ---------------- 最新版本安装 ----------------

    /** 连点 9 次版本号：解锁并显示「安装我们的最新版本」入口 */
    private fun unlockUpdateEntry() {
        if (!isAdded) return
        userData.updateEntryUnlocked = true
        if (binding.cardUpdate.visibility != View.VISIBLE) {
            binding.cardUpdate.visibility = View.VISIBLE
            Toast.makeText(requireContext(), "已解锁最新版本安装入口", Toast.LENGTH_SHORT).show()
        }
    }

    /** 下载官方最新版 → 校验 → 通过设备官方接口安装 */
    private fun startInstallLatest() {
        if (!isAdded || updateBusy) return
        val ctx = requireContext()
        val appContext = ctx.applicationContext
        updateBusy = true

        val content = LayoutInflater.from(ctx).inflate(R.layout.dialog_update_progress, null)
        val tvStatus = content.findViewById<TextView>(R.id.tvUpdateStatus)
        val progressBar = content.findViewById<ProgressBar>(R.id.pbUpdateProgress)
        tvStatus.text = "正在连接服务器…"
        progressBar.isIndeterminate = true

        val updateManager = UpdateManager(appContext)
        updater = updateManager
        var userCancelled = false

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setView(content)
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ ->
                userCancelled = true
                updateBusy = false
                updateManager.cancel()
            }
            .show()

        Thread {
            updateManager.downloadLatest(object : UpdateManager.DownloadCallback {
                override fun onProgress(downloaded: Long, total: Long) {
                    mainHandler.post {
                        if (userCancelled) return@post
                        if (total > 0L) {
                            progressBar.isIndeterminate = false
                            val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            progressBar.progress = percent
                            tvStatus.text =
                                "${formatSize(downloaded)} / ${formatSize(total)} · $percent%"
                        } else {
                            progressBar.isIndeterminate = true
                            tvStatus.text = "已下载 ${formatSize(downloaded)}"
                        }
                    }
                }

                override fun onSuccess(apk: File, verification: UpdateManager.VerifyResult) {
                    mainHandler.post {
                        dismissQuietly(dialog)
                        updateBusy = false
                        if (!isAdded || userCancelled) return@post
                        when (verification) {
                            is UpdateManager.VerifyResult.SignatureMismatch ->
                                showSignatureMismatch(
                                    apk,
                                    requireContext().packageName,
                                    verification.reason
                                )

                            is UpdateManager.VerifyResult.External ->
                                showExternalPackage(
                                    apk,
                                    verification.packageName,
                                    verification.versionName,
                                    verification.installedVersionCode > 0L
                                )

                            is UpdateManager.VerifyResult.SelfUpdate ->
                                installLatestApk(apk, requireContext().packageName)

                            is UpdateManager.VerifyResult.Rejected ->
                                Toast.makeText(
                                    requireContext(),
                                    verification.reason,
                                    Toast.LENGTH_LONG
                                ).show()
                        }
                    }
                }

                override fun onFailure(message: String) {
                    mainHandler.post {
                        dismissQuietly(dialog)
                        updateBusy = false
                        if (!isAdded || userCancelled) return@post
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("无法完成更新")
                            .setMessage(message)
                            .setPositiveButton("重试") { _, _ -> startInstallLatest() }
                            .setNegativeButton("关闭", null)
                            .show()
                    }
                }
            })
        }.start()
    }

    /**
     * 安装已下载并校验通过的 APK。
     *
     * 通道顺序对齐 3.2.6：先探测设备官方接口（zybos_manager），可用则静默安装并验证；
     * 被系统签名校验封禁或设备没有该接口时，明确告知原因并引导系统安装器。
     */
    private fun installLatestApk(apk: File, targetPackage: String) {
        if (!isAdded) return
        val ctx = requireContext()
        val appContext = ctx.applicationContext
        val isSelfUpdate = targetPackage == appContext.packageName

        // 先做预检：签名/降级问题提前拦下，避免装到一半被系统拒绝
        when (val check = ApkInstaller.preflight(appContext, apk, targetPackage)) {
            is ApkInstaller.Preflight.SignatureMismatch -> {
                showSignatureMismatch(
                    apk,
                    targetPackage,
                    "本机已安装的 $targetPackage 与这个安装包不是同一把证书签出的。"
                )
                return
            }

            is ApkInstaller.Preflight.Downgrade -> {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("已阻止降级安装")
                    .setMessage("安装包版本（${check.incoming}）低于本机已安装版本（${check.installed}）。")
                    .setPositiveButton("关闭", null)
                    .show()
                return
            }

            is ApkInstaller.Preflight.Invalid -> {
                Toast.makeText(ctx, check.reason, Toast.LENGTH_LONG).show()
                return
            }

            is ApkInstaller.Preflight.Ready -> Unit
        }

        val content = LayoutInflater.from(ctx).inflate(R.layout.dialog_update_progress, null)
        val tvStatus = content.findViewById<TextView>(R.id.tvUpdateStatus)
        val progressBar = content.findViewById<ProgressBar>(R.id.pbUpdateProgress)
        progressBar.visibility = View.GONE
        tvStatus.text = "正在检测设备官方安装接口…"

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (isSelfUpdate) "安装新版本" else "安装 $targetPackage")
            .setView(content)
            .setCancelable(false)
            .setNegativeButton("取消", null)
            .show()

        ApkInstaller.installAsync(appContext, apk, targetPackage, object : ApkInstaller.Callback {
            override fun onStage(stage: String) {
                if (!isAdded) return
                tvStatus.text = stage
            }

            override fun onResult(result: ApkInstaller.Result) {
                dismissQuietly(dialog)
                if (!isAdded) return
                if (result.success) {
                    val text = when {
                        isSelfUpdate && result.verified -> "新版本已安装完成，本应用即将更新重启"
                        isSelfUpdate -> "安装请求已提交，稍后会自动更新"
                        result.verified -> "安装完成"
                        else -> "安装请求已提交，${result.channel?.label ?: "系统"}正在处理"
                    }
                    Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
                } else if (result.needsUserConfirm) {
                    showSystemInstallerFallback(apk, result.message)
                } else {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("安装失败")
                        .setMessage(result.message)
                        .setPositiveButton("重试") { _, _ -> installLatestApk(apk, targetPackage) }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            }
        })
    }

    /** 静默通道不可用时的兜底：改用系统安装器（用户手动确认） */
    private fun showSystemInstallerFallback(apk: File, reason: String) {
        if (!isAdded) return
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("静默安装不可用")
            .setMessage("$reason\n\n可以改用系统安装器完成安装，系统会弹窗请你确认。")
            .setPositiveButton("改用系统安装器") { _, _ ->
                if (!ApkInstaller.canRequestInstall(ctx)) {
                    pendingInstallApk = apk
                    promptEnableInstallPermission()
                } else {
                    launchSystemInstaller(apk)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 下载包的签名与本机不一致：说明不是同一把私钥签出的包，需明确告知后果 */
    private fun showSignatureMismatch(apk: File, targetPackage: String, reason: String) {
        if (!isAdded) return
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("安装包签名不一致")
            .setMessage(
                "$reason\n\n" +
                    "签名不同的包无法覆盖安装，必须先卸载本机已安装的 $targetPackage，" +
                    "再安装这个包。卸载会清除该应用的数据。"
            )
            .setPositiveButton("先卸载") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$targetPackage")))
                    Toast.makeText(ctx, "卸载完成后请重新选择安装包", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(ctx, "无法打开卸载界面", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 分发地址提供的不是本应用：说明这是独立应用，确认后按普通 APK 安装 */
    private fun showExternalPackage(
        apk: File,
        packageName: String,
        versionName: String,
        alreadyInstalled: Boolean
    ) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("检测到独立应用安装包")
            .setMessage(
                "下载地址当前提供的是另一个应用：\n\n" +
                    "包名：$packageName\n" +
                    "版本：$versionName\n" +
                    (if (alreadyInstalled) "本机已安装该应用，将执行覆盖升级。\n\n" else "\n") +
                    "它不是本应用（${requireContext().packageName}）。要继续安装吗？"
            )
            .setPositiveButton("继续安装") { _, _ -> installLatestApk(apk, packageName) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dismissQuietly(dialog: AlertDialog?) {
        try {
            if (dialog != null && dialog.isShowing) dialog.dismiss()
        } catch (e: Exception) {
            // 页面已销毁时忽略
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return if (index == 0) {
            "$bytes B"
        } else {
            String.format(Locale.getDefault(), "%.1f %s", value, units[index])
        }
    }

    // ---------------- 设置 / 关于 / 开发者信息 ----------------

    private fun showSettingsDialog() {
        val options = arrayOf(
            "清除查询历史",
            "清除已学记录",
            "清除收藏单词",
            "清除全部数据"
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("设置")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("确认清除")
                            .setMessage("确定要清除所有查询历史吗？")
                            .setPositiveButton("确定") { _, _ ->
                                userData.clearHistory()
                                Toast.makeText(requireContext(), "已清除查询历史", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    1 -> {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("确认清除")
                            .setMessage("确定要清除所有已学记录吗？")
                            .setPositiveButton("确定") { _, _ ->
                                userData.clearLearned()
                                Toast.makeText(requireContext(), "已清除已学记录", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    2 -> {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("确认清除")
                            .setMessage("确定要清除所有收藏单词吗？")
                            .setPositiveButton("确定") { _, _ ->
                                userData.clearFavorites()
                                Toast.makeText(requireContext(), "已清除收藏单词", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    3 -> {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("确认清除")
                            .setMessage("确定要清除全部数据吗？此操作不可恢复！")
                            .setPositiveButton("确定") { _, _ ->
                                userData.clearAll()
                                Toast.makeText(requireContext(), "已清除全部数据", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showAboutDialog() {
        val versionName = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        } catch (e: Exception) {
            "1.0.0"
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("关于英语天地")
            .setMessage(
                "应用名称：英语天地\n" +
                "版本号：v$versionName\n\n" +
                "英语天地是一款专注于中小学英语学习的免费应用，" +
                "提供单词查询、分级学习、收藏管理、测试练习等功能。\n\n" +
                "本应用完全免费、纯本地运行，不收集任何个人信息。\n\n" +
                "官网：https://georain.github.io/EnglishWorld"
            )
            .setPositiveButton("访问官网") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://georain.github.io/EnglishWorld"))
                startActivity(intent)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showDeveloperInfoDialog() {
        val ctx = requireContext()
        val pInfo = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        } catch (e: Exception) {
            null
        }
        val versionName = pInfo?.versionName ?: "1.0.0"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pInfo?.longVersionCode?.toString() ?: "-"
        } else {
            @Suppress("DEPRECATION")
            pInfo?.versionCode?.toString() ?: "-"
        }
        val installTime = pInfo?.let {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(it.lastUpdateTime))
        } ?: "-"

        val allWords = wordDatabase.getAllWords()
        val primaryCount = allWords.count { it.level == "小学" }
        val middleCount = allWords.count { it.level == "初中" }
        val highCount = allWords.count { it.level == "高中" }

        val info = buildString {
            appendLine("【应用信息】")
            appendLine("包名：${ctx.packageName}")
            appendLine("版本名：v$versionName")
            appendLine("版本号：$versionCode")
            appendLine("安装/更新时间：$installTime")
            appendLine()
            appendLine("【词库统计】")
            appendLine("总词数：${allWords.size}")
            appendLine("小学：$primaryCount  初中：$middleCount  高中：$highCount")
            appendLine()
            appendLine("【设备信息】")
            appendLine("型号：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI：${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("【安装通道】")
            appendLine("官方安装接口：${ApkInstaller.probe(ctx).message}")
            appendLine("隐藏 API 绕过：${HiddenApi.selfTest()}")
            appendLine("下载地址：${UpdateManager.sources(ctx).first()}")
            appendLine()
            appendLine("【用户数据】")
            appendLine("已学习天数：${userData.studyDays}")
            appendLine("收藏数：${userData.getFavorites().size}")
            appendLine("历史数：${userData.getHistory().size}")
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle("开发者调试信息")
            .setMessage(info)
            .setPositiveButton("安装本地 APK") { _, _ ->
                startPickApkForUpdate()
            }
            .setNeutralButton("更多") { _, _ ->
                showDeveloperToolsDialog(info)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 开发者工具：复制调试信息 / 修改安装包下载地址 */
    private fun showDeveloperToolsDialog(info: String) {
        if (!isAdded) return
        val ctx = requireContext()
        val options = arrayOf("复制调试信息", "设置安装包下载地址", "恢复默认下载地址")
        MaterialAlertDialogBuilder(ctx)
            .setTitle("开发者工具")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("debug_info", info))
                        Toast.makeText(ctx, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }

                    1 -> promptCustomSource()

                    2 -> {
                        UpdateManager.saveCustomSource(ctx, "")
                        Toast.makeText(ctx, "已恢复默认下载地址", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 自定义 APK 下载地址（留空即恢复默认） */
    private fun promptCustomSource() {
        if (!isAdded) return
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            setText(UpdateManager.customSource(ctx))
            hint = "https://example.com/app.apk"
            setSingleLine()
        }
        val container = android.widget.FrameLayout(ctx).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("安装包下载地址")
            .setView(container)
            .setMessage("留空则使用内置默认地址。地址必须直接返回 APK 文件。")
            .setPositiveButton("保存") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                    Toast.makeText(ctx, "地址必须以 http:// 或 https:// 开头", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                UpdateManager.saveCustomSource(ctx, url)
                Toast.makeText(ctx, if (url.isEmpty()) "已恢复默认地址" else "已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 打开系统文件选择器挑选 APK（仅限 .apk / application 类型） */
    private fun startPickApkForUpdate() {
        try {
            pickApkLauncher.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    /** 处理选中的 APK：解析 → 确认 → 走统一安装通道（先官方接口，再系统安装器） */
    private fun handlePickedApk(uri: Uri) {
        val ctx = requireContext()
        // 1. 复制到应用缓存目录（SAF 的 content Uri 系统安装器通常读不了，需转成自有文件）
        val apkFile = copyUriToCache(uri)
        if (apkFile == null) {
            Toast.makeText(ctx, "读取安装包失败", Toast.LENGTH_SHORT).show()
            return
        }
        // 2. 解析包名与版本
        val info = try {
            ctx.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        } catch (e: Exception) {
            null
        }
        val pkgName = info?.packageName
        if (pkgName == null) {
            Toast.makeText(ctx, "无效的安装包（无法解析）", Toast.LENGTH_LONG).show()
            apkFile.delete()
            return
        }
        // 3. 本应用的包直接装；其它应用先确认（对齐 3.2.6 的「安装本地 APK」）
        if (pkgName == ctx.packageName) {
            installLatestApk(apkFile, pkgName)
            return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("确认安装")
            .setMessage(
                "包名：$pkgName\n" +
                    "版本：${info?.versionName ?: "-"}\n\n" +
                    "这不是本应用（${ctx.packageName}）的安装包，安装后会出现一个新的应用。继续吗？"
            )
            .setPositiveButton("安装") { _, _ -> installLatestApk(apkFile, pkgName) }
            .setNegativeButton("取消") { _, _ -> apkFile.delete() }
            .show()
    }

    /** 把选中的 content Uri 复制到 cacheDir/apk/update.apk */
    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val ctx = requireContext()
            val dir = File(ctx.cacheDir, "apk").apply { mkdirs() }
            val out = File(dir, "update.apk")
            if (out.exists()) out.delete()
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (out.length() <= 0L) null else out
        } catch (e: Exception) {
            null
        }
    }

    /** 引导用户到系统设置开启“允许安装未知应用” */
    private fun promptEnableInstallPermission() {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("需要安装权限")
            .setMessage("系统尚未允许本应用安装安装包。请在接下来的设置页开启“允许安装未知应用”，然后返回本页重新点击安装入口。")
            .setPositiveButton("去设置") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${ctx.packageName}")
                            )
                        )
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 通过 FileProvider 生成 Uri 并拉起系统安装器 */
    private fun launchSystemInstaller(apkFile: File) {
        val ctx = requireContext()
        if (!ApkInstaller.launchSystemInstaller(ctx, apkFile)) {
            Toast.makeText(ctx, "无法启动系统安装器", Toast.LENGTH_LONG).show()
        }
    }

    /** 从“允许安装未知应用”设置页返回后，若已有待装 APK 且权限已授予则继续安装 */
    override fun onResume() {
        super.onResume()
        val pending = pendingInstallApk ?: return
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            requireContext().packageManager.canRequestPackageInstalls()
        if (!granted) return
        pendingInstallApk = null
        launchSystemInstaller(pending)
    }

    override fun onDestroyView() {
        updater?.cancel()
        updater = null
        updateBusy = false
        super.onDestroyView()
        _binding = null
    }
}
