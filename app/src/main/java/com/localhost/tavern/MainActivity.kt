package com.localhost.tavern

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import java.io.File
import java.io.FileOutputStream

/**
 * 酒馆 — 极简 WebView 浏览器
 *
 * 功能：
 * - 冷启动弹窗设置目标 URL + 选择全屏/普通模式
 * - 热启动直接读取已保存 URL 加载
 * - 全屏沉浸式隐藏状态栏和导航栏
 * - WebView 长按 5 秒触发重置对话框
 * - HTTP 文件下载（系统 DownloadManager）
 * - Blob 文件下载（JavaScript 桥 + base64 解码）
 * - 文件上传（系统文件选择器，不申请存储权限）
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** SharedPreferences 名称 */
        private const val PREFS_NAME = "tavern_prefs"
        /** 目标 URL 存储键 */
        private const val KEY_TARGET_URL = "target_url"
        /** 默认目标 URL */
        private const val DEFAULT_URL = "http://127.0.0.1:8000"
    }

    private lateinit var webView: WebView

    /** 下载管理器 */
    private lateinit var downloadManager: DownloadManager

    /** 当前下载文件名（用于 Toast 提示） */
    private var currentDownloadFileName: String? = null

    /** 下载完成广播接收器 */
    private var downloadReceiver: BroadcastReceiver? = null

    /**
     * 文件上传回调 — 由 WebChromeClient.onShowFileChooser 设置，
     * 在 onDestroy 中清理引用防止内存泄漏
     */
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // ==================================================================
    //  SharedPreferences — 持久化目标 URL
    // ==================================================================

    /** SharedPreferences 实例（懒加载） */
    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    /** 读取已保存的目标 URL */
    private fun getSavedUrl(): String =
        prefs.getString(KEY_TARGET_URL, DEFAULT_URL) ?: DEFAULT_URL

    /** 保存目标 URL 到 SharedPreferences */
    private fun saveUrl(url: String) {
        prefs.edit().putString(KEY_TARGET_URL, url).apply()
    }

    // ==================================================================
    //  长按 5 秒触发重置 — Handler + setOnLongClickListener
    // ==================================================================

    /** 主线程 Handler，用于长按计时 */
    private val resetHandler = Handler(Looper.getMainLooper())

    /** 长按 5 秒后执行的重置任务 */
    private val resetRunnable = Runnable { showResetDialog() }

    // ==================================================================
    //  文件选择器启动器
    // ==================================================================

    /**
     * 文件选择器启动器 — 使用 Activity Result API，
     * 无需申请存储权限
     */
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            filePathCallback?.onReceiveValue(uris.toTypedArray())
        } else {
            // 用户取消了选择
            filePathCallback?.onReceiveValue(null)
        }
        // 释放回调引用，避免重复调用
        filePathCallback = null
    }

    // ==================================================================
    //  生命周期
    // ==================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager

        setupWebView()

        if (savedInstanceState == null) {
            // 冷启动：弹出 URL 设置 + 模式选择对话框
            showModeSelectionDialog()
        } else {
            // 热启动：直接读取已保存 URL 加载，不弹窗
            webView.loadUrl(getSavedUrl())
        }
    }

    // ==================================================================
    //  WebView 初始化（仅配置，不在此处加载 URL）
    // ==================================================================

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.apply {
            // 启用 JavaScript（加载 localhost 页面必需）
            settings.javaScriptEnabled = true
            // 允许 DOM 存储
            settings.domStorageEnabled = true
            // 允许文件访问（某些上传控件需要）
            settings.allowFileAccess = true
            // 允许混合内容（HTTP/HTTPS 混用）
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // 启用 Cookie
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            // 自定义 WebViewClient：首次页面加载时注入 blob 拦截脚本
            webViewClient = object : WebViewClient() {
                private var blobHookInjected = false

                override fun onPageFinished(view: WebView, url: String) {
                    if (!blobHookInjected) {
                        blobHookInjected = true
                        view.evaluateJavascript(BLOB_INTERCEPT_SCRIPT, null)
                    }
                }
            }
            webChromeClient = TavernWebChromeClient()

            // 注册 JavaScript 桥 — 用于处理 blob: 协议下载（角色卡导出等）
            addJavascriptInterface(TavernJsBridge(), "TavernBridge")

            // 拦截下载请求
            setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val mime = mimeType ?: "application/octet-stream"

                when {
                    url.startsWith("http://") || url.startsWith("https://") -> {
                        startDownload(url, fileName)
                        Toast.makeText(applicationContext, "开始下载：$fileName", Toast.LENGTH_SHORT).show()
                    }
                    url.startsWith("blob:") -> {
                        startBlobDownload(url, fileName, mime)
                    }
                    // data: / content: 等协议由 WebView 内部处理
                }
            }

            // 长按 5 秒触发重置对话框
            setupLongPressReset()
        }
    }

    /**
     * 注入页面初始化脚本 — 包装 URL.createObjectURL，
     * 在 blob 被创建时立即缓存引用，防止后续被 revoke 后无法读取。
     * 页面可通过 window._tavernGetBlob(url, callback) 获取缓存的 blob 数据。
     */
    private val BLOB_INTERCEPT_SCRIPT = """
        (function() {
            var _create = URL.createObjectURL.bind(URL);
            var _revoke = URL.revokeObjectURL.bind(URL);
            var _store = {};

            URL.createObjectURL = function(blob) {
                var url = _create(blob);
                _store[url] = blob;           // 缓存 blob 引用，防止被 GC
                return url;
            };

            URL.revokeObjectURL = function(url) {
                _revoke(url);
                // 注意：不删除 _store[url]！外部可能仍需读取
            };

            // 暴露给下载处理器：从缓存中读取 blob 并转为 base64
            window._tavernGetBlob = function(url, fileName, mimeType) {
                var blob = _store[url];
                if (!blob) {
                    TavernBridge.onBlobError('blob 已过期，请重新导出');
                    return;
                }
                var reader = new FileReader();
                reader.onload = function() {
                    TavernBridge.onBlobReady(reader.result, fileName, mimeType);
                };
                reader.onerror = function() {
                    TavernBridge.onBlobError('blob 读取失败');
                };
                reader.readAsDataURL(blob);
                delete _store[url];   // 用完即删，释放内存
            };
        })();
    """.trimIndent()

    // ==================================================================
    //  长按 5 秒重置
    // ==================================================================

    /**
     * 注册长按监听。
     * 系统检测到长按（约 500ms）后，通过 Handler 延时 5000ms；
     * 若中途手指抬起或移动则取消，避免误触。
     */
    /**
     * 注册触摸监听实现 5 秒长按重置，同时不拦截 WebView 原生事件。
     * 之前用 setOnLongClickListener（返回 true 消费事件）导致 WebView 收不到
     * 长按 → 文字选择 ActionMode 无法弹出。改为纯 OnTouchListener 计时方案：
     * ACTION_DOWN 启动 5 秒倒计时，UP/CANCEL 时取消，始终返回 false 不消费。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupLongPressReset() {
        webView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resetHandler.removeCallbacks(resetRunnable)
                    resetHandler.postDelayed(resetRunnable, 5000)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    resetHandler.removeCallbacks(resetRunnable)
                }
                // ACTION_MOVE 不取消——手指轻微抖动不影响计时
            }
            false // 不消费！WebView 正常处理触摸、长按选字、点击等
        }
    }

    // ==================================================================
    //  模式选择对话框（冷启动）
    // ==================================================================

    /**
     * 冷启动对话框 —— URL 输入框 + 两种模式按钮。
     * 用户必须输入/确认 URL 并选择模式后才能进入应用。
     */
    private fun showModeSelectionDialog() {
        val editText = EditText(this).apply {
            setText(getSavedUrl())
            setSingleLine() // 单行输入
        }

        val hintText = TextView(this).apply {
            text = "💡 进入软件后可以长按屏幕 5 秒重置链接"
            textSize = 13f
            setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
        }

        // 给 EditText 添加边距包装
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(editText)
            addView(hintText)
        }

        AlertDialog.Builder(this)
            .setTitle("设置目标 URL 并选择模式")
            .setView(container)
            .setPositiveButton("🌐 全屏模式") { _, _ ->
                applyUrlAndMode(editText, fullScreen = true)
            }
            .setNegativeButton("📱 普通模式") { _, _ ->
                applyUrlAndMode(editText, fullScreen = false)
            }
            .setCancelable(false) // 必须选择，不可通过返回键跳过
            .show()
    }

    // ==================================================================
    //  重置对话框（长按 5 秒触发）
    // ==================================================================

    /**
     * 重置对话框 —— 与冷启动弹窗相同布局，但允许取消。
     * 长按 5 秒后触发，用于重新设置 URL 和切换模式。
     */
    private fun showResetDialog() {
        val editText = EditText(this).apply {
            setText(getSavedUrl())
            setSingleLine()
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(editText)
        }

        AlertDialog.Builder(this)
            .setTitle("重置目标 URL")
            .setView(container)
            .setPositiveButton("🌐 全屏模式") { _, _ ->
                applyUrlAndMode(editText, fullScreen = true)
            }
            .setNegativeButton("📱 普通模式") { _, _ ->
                applyUrlAndMode(editText, fullScreen = false)
            }
            .setNeutralButton("取消", null) // 允许放弃重置
            .show()
    }

    // ==================================================================
    //  URL 保存 + 模式切换 + 页面加载
    // ==================================================================

    /**
     * 从 EditText 提取 URL → 保存到 SharedPreferences → 切换模式 → 加载页面。
     * 冷启动弹窗和重置对话框共用此方法。
     */
    private fun applyUrlAndMode(editText: EditText, fullScreen: Boolean) {
        val url = editText.text.toString().trim().ifEmpty { DEFAULT_URL }
        saveUrl(url)

        if (fullScreen) {
            enterFullScreenMode()
        } else {
            enterNormalMode()
        }

        // 如果 WebView 已有原始 URL 则 reload，否则 loadUrl
        if (webView.url != null) {
            webView.loadUrl(url)
        } else {
            webView.loadUrl(url)
        }
    }

    // ==================================================================
    //  全屏 / 普通模式切换
    // ==================================================================

    /**
     * 进入全屏沉浸式模式 — 隐藏状态栏和底部导航栏。
     * API 30+ 使用 WindowInsetsController（不影响文字选择 ActionMode），
     * 低版本使用 SYSTEM_UI_FLAG_* 方案。
     */
    private fun enterFullScreenMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 让内容延伸到系统栏区域
            window.setDecorFitsSystemWindows(false)
            val controller = window.insetsController
            controller?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller?.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    /** 进入普通模式 — 保留系统状态栏和导航栏（包括 ActionMode 弹出栏） */
    private fun enterNormalMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
            val controller = window.insetsController
            controller?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            WindowCompat.setDecorFitsSystemWindows(window, true)
        }
    }

    // ==================================================================
    //  文件下载 — 使用系统 DownloadManager，不申请存储权限
    // ==================================================================

    /**
     * 启动下载任务
     * @param url 下载地址
     * @param fileName 保存的文件名
     */
    private fun startDownload(url: String, fileName: String) {
        currentDownloadFileName = fileName

        val request = DownloadManager.Request(url.toUri()).apply {
            setTitle(fileName)
            setDescription("正在下载…")

            // Android 10+ 使用 MediaStore URI（兼容分区存储），低版本用旧 API
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
                }
                setDestinationUri(
                    contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        contentValues
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    fileName
                )
            }

            // 下载完成后发送通知
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
        }

        val downloadId = downloadManager.enqueue(request)

        // 动态注册下载完成广播
        registerDownloadReceiver(downloadId)
    }

    /** 动态注册下载完成广播接收器 */
    private fun registerDownloadReceiver(downloadId: Long) {
        // 先注销旧的接收器
        unregisterDownloadReceiver()

        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1
                if (id == downloadId) {
                    val fileName = currentDownloadFileName ?: "未知文件"
                    Toast.makeText(
                        applicationContext,
                        "文件已保存至 内部存储/Download/$fileName",
                        Toast.LENGTH_LONG
                    ).show()
                    // 任务完成，注销接收器
                    unregisterDownloadReceiver()
                }
            }
        }

        // API 33+ 需要显式声明接收器是否导出，低版本用两参数版本
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(downloadReceiver, filter)
        }
    }

    /** 注销下载完成广播接收器 */
    private fun unregisterDownloadReceiver() {
        downloadReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
                // 接收器可能已被系统注销，忽略异常
            }
            downloadReceiver = null
        }
    }

    // ==================================================================
    //  Blob 下载 — JavaScript 桥将 blob 内容传给原生层保存
    // ==================================================================

    /**
     * 通过预先注入的 _tavernGetBlob 从缓存读取 blob 内容。
     * 页面初始化时已通过 BLOB_INTERCEPT_SCRIPT 拦截了 URL.createObjectURL，
     * blob 引用已被缓存，无需再次 fetch。
     */
    private fun startBlobDownload(blobUrl: String, fileName: String, mimeType: String) {
        Toast.makeText(applicationContext, "正在导出：$fileName", Toast.LENGTH_SHORT).show()

        currentDownloadFileName = fileName
        val safeFileName = fileName.replace("'", "\\'")
        val safeMime = mimeType.replace("'", "\\'")
        val safeBlobUrl = blobUrl.replace("'", "\\'")

        val jsCode = """
            window._tavernGetBlob('$safeBlobUrl', '$safeFileName', '$safeMime');
        """.trimIndent()

        webView.evaluateJavascript(jsCode, null)
    }

    /**
     * JavaScript 桥 — 网页端通过 TavernBridge.onBlobReady(dataUrl, ...)
     * 将 blob 的 base64 数据回传给原生层写入文件
     */
    private inner class TavernJsBridge {

        @JavascriptInterface
        fun onBlobReady(dataUrl: String, fileName: String, mimeType: String) {
            runOnUiThread {
                saveBlobFile(dataUrl, fileName, mimeType)
            }
        }

        @JavascriptInterface
        fun onBlobError(message: String) {
            runOnUiThread {
                Toast.makeText(
                    applicationContext,
                    "导出失败：$message",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** 将 base64 data URL 解码并写入文件（Data URL 格式: "data:mime/type;base64,XXX"） */
    private fun saveBlobFile(dataUrl: String, fileName: String, mimeType: String) {
        try {
            val base64Data = dataUrl.substringAfter(";base64,")
            val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
                }
                val uri = contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    contentValues
                )
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { out -> out.write(decodedBytes) }
                    Toast.makeText(
                        applicationContext,
                        "文件已保存至 内部存储/Download/$fileName",
                        Toast.LENGTH_LONG
                    ).show()
                } ?: throw Exception("无法创建文件")
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                dir.mkdirs()
                val file = File(dir, fileName)
                FileOutputStream(file).use { it.write(decodedBytes) }
                Toast.makeText(
                    applicationContext,
                    "文件已保存至 内部存储/Download/$fileName",
                    Toast.LENGTH_LONG
                ).show()
            }

            currentDownloadFileName = null
        } catch (e: Exception) {
            Toast.makeText(
                applicationContext,
                "保存失败：${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ==================================================================
    //  自定义 WebChromeClient — 处理文件上传
    // ==================================================================

    private inner class TavernWebChromeClient : WebChromeClient() {

        /**
         * 处理 HTML 文件选择器（<input type="file">）。
         * 使用系统文件选择器，不申请存储权限。
         */
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            // 先释放之前的回调引用
            this@MainActivity.filePathCallback?.onReceiveValue(null)
            this@MainActivity.filePathCallback = filePathCallback

            // 启动系统文件选择器（支持多选，所有文件类型）
            fileChooserLauncher.launch("*/*")
            return true
        }
    }

    // ==================================================================
    //  生命周期管理
    // ==================================================================

    override fun onDestroy() {
        // 清理长按 Handler
        resetHandler.removeCallbacks(resetRunnable)

        // 注销下载完成广播
        unregisterDownloadReceiver()

        // 释放文件上传回调，防止内存泄漏
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null

        // 安全销毁 WebView
        webView.apply {
            stopLoading()
            removeAllViews()
            destroy()
        }

        super.onDestroy()
    }
}
