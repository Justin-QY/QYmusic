package com.qymusic.app.ui.activity

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.lifecycleScope
import com.drake.net.Get
import com.drake.tooltip.toast
import kotlinx.coroutines.launch
import com.qymusic.app.R
import com.qymusic.app.config.Api
import com.qymusic.app.config.SPConfig
import com.qymusic.app.databinding.ActivityNeteaseLoginBinding
import com.qymusic.app.model.PlatformAccount

/**
 * 云端账号登录（网易云 / QQ 音乐 / 酷我通用）。
 *
 * 登录页在 WebView 内完成，登录成功后从请求头抓取 Cookie（多数是 HttpOnly），
 * 再调服务端换取账号信息，只保存在本机。
 */
class PlatformLoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLATFORM = "platform"

        const val PLATFORM_NETEASE = "netease"
        const val PLATFORM_QQ = "qq"
        const val PLATFORM_KUWO = "kuwo"

        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val VIEWPORT_JS =
            "(function(){var m=document.querySelector('meta[name=\"viewport\"]');" +
                "if(!m){m=document.createElement('meta');m.name='viewport';" +
                "(document.head||document.documentElement).appendChild(m);}" +
                "m.setAttribute('content','width=1280');})()"

        private val CONFIG = mapOf(
            PLATFORM_NETEASE to LoginConfig(
                "https://music.163.com/#/login",
                listOf("https://music.163.com"),
                listOf("MUSIC_U="),
                Api.NETEASE_ACCOUNT,
                false
            ),
            PLATFORM_QQ to LoginConfig(
                "https://y.qq.com/",
                listOf(
                    "https://y.qq.com", "https://u.y.qq.com", "https://c.y.qq.com",
                    "https://graph.qq.com", "https://ssl.ptlogin2.qq.com"
                ),
                listOf("qqmusic_key=", "qm_keyst="),
                Api.QQ_ACCOUNT,
                true
            ),
            PLATFORM_KUWO to LoginConfig(
                "https://www.kuwo.cn/",
                listOf(
                    "https://www.kuwo.cn", "https://kuwo.cn",
                    "https://m.kuwo.cn", "https://passport.kuwo.cn"
                ),
                listOf("kw_token="),
                Api.KUWO_ACCOUNT,
                true
            ),
        )

        private data class LoginConfig(
            val url: String,
            val cookieDomains: List<String>,
            val cookieKeys: List<String>,
            val accountApi: String,
            val desktop: Boolean,
        )
    }

    private lateinit var binding: ActivityNeteaseLoginBinding
    private lateinit var config: LoginConfig
    private lateinit var platform: String
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_netease_login)

        platform = intent.getStringExtra(EXTRA_PLATFORM) ?: PLATFORM_NETEASE
        config = CONFIG[platform] ?: CONFIG.getValue(PLATFORM_NETEASE)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)
        // 每次登录都使用干净会话，避免直接命中上次登录态
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = if (config.desktop) DESKTOP_UA else MOBILE_UA
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progress.visibility = android.view.View.GONE
                if (config.desktop) {
                    view?.evaluateJavascript(VIEWPORT_JS, null)
                }
                checkCookie()
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val cookieHeader = request?.requestHeaders?.get("Cookie")
                if (!cookieHeader.isNullOrBlank() && matchesCookie(cookieHeader)) {
                    handleCookie(cookieHeader)
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        binding.webView.loadUrl(config.url)

        binding.webView.postDelayed(object : Runnable {
            override fun run() {
                checkCookie()
                binding.webView.postDelayed(this, 600)
            }
        }, 600)
    }

    private fun matchesCookie(cookie: String): Boolean =
        config.cookieKeys.any { cookie.contains(it) }

    /** QQ/酷我的登录 Cookie 分散在多个子域，需要合并读取 */
    private fun readCookies(): String {
        val merged = LinkedHashMap<String, String>()
        config.cookieDomains.forEach { domain ->
            val header = runCatching {
                CookieManager.getInstance().getCookie(domain)
            }.getOrNull() ?: return@forEach
            header.split(";").forEach { part ->
                val index = part.indexOf('=')
                if (index > 0) {
                    merged.putIfAbsent(
                        part.substring(0, index).trim(),
                        part.substring(index + 1).trim()
                    )
                }
            }
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun checkCookie() {
        val cookie = readCookies()
        if (cookie.isNotBlank() && matchesCookie(cookie)) {
            handleCookie(cookie)
        }
    }

    private fun handleCookie(cookie: String) {
        if (handled) {
            return
        }
        handled = true
        binding.progress.visibility = android.view.View.VISIBLE
        lifecycleScope.launch {
            try {
                val account = Get<PlatformAccount>(config.accountApi) {
                    addQuery("cookie", cookie)
                }.await()
                when (platform) {
                    PLATFORM_QQ -> SPConfig.saveQqLogin(
                        cookie, account.userId.toString(), account.nickname, account.avatarUrl
                    )
                    PLATFORM_KUWO -> SPConfig.saveKuwoLogin(
                        cookie, account.userId, account.nickname, account.avatarUrl
                    )
                    else -> SPConfig.saveNeteaseLogin(
                        cookie, account.userId, account.nickname, account.avatarUrl
                    )
                }
                toast("登录成功")
                setResult(RESULT_OK)
                // 登录凭据已经拿到，清掉 WebView 里的会话，避免切换账号时复用
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                finish()
            } catch (e: Exception) {
                handled = false
                binding.progress.visibility = android.view.View.GONE
                toast(e.message ?: "登录失败，请重试")
            }
        }
    }

    override fun onDestroy() {
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }
}
