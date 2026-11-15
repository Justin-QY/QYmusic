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

        private val CONFIG = mapOf(
            PLATFORM_NETEASE to LoginConfig(
                "https://music.163.com/#/login", "https://music.163.com", listOf("MUSIC_U="), Api.NETEASE_ACCOUNT
            ),
            PLATFORM_QQ to LoginConfig(
                "https://xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=716027609&daid=383&style=33&hide_title_bar=1&hide_border=1&target=self&s_url=https%3A%2F%2Fy.qq.com%2Fportal%2Fprofile.html",
                "https://y.qq.com", listOf("qqmusic_key=", "qm_keyst="), Api.QQ_ACCOUNT
            ),
            PLATFORM_KUWO to LoginConfig(
                "https://passport.kuwo.cn/", "https://www.kuwo.cn", listOf("kw_token="), Api.KUWO_ACCOUNT
            ),
        )

        private data class LoginConfig(
            val url: String,
            val cookieDomain: String,
            val cookieKeys: List<String>,
            val accountApi: String,
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
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progress.visibility = android.view.View.GONE
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
                binding.webView.postDelayed(this, 1500)
            }
        }, 1500)
    }

    private fun matchesCookie(cookie: String): Boolean =
        config.cookieKeys.any { cookie.contains(it) }

    private fun checkCookie() {
        val cookie = CookieManager.getInstance().getCookie(config.cookieDomain) ?: return
        if (matchesCookie(cookie)) {
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
