package com.qymusic.app.ui.activity

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import com.qymusic.app.model.NeteaseAccount

/**
 * 网易云网页登录：登录成功后从 WebView 取 Cookie（MUSIC_U），
 * 本地保存并换取账号信息。Cookie 只保存在本机。
 */
class NeteaseLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNeteaseLoginBinding
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_netease_login)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)
        // 每次进入登录页都使用干净的会话，避免直接命中上一次的登录态
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()

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

            // MUSIC_U 是 HttpOnly，CookieManager.getCookie 可能取不到，
            // 从 WebView 实际发出的请求头里取更可靠
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val cookieHeader = request?.requestHeaders?.get("Cookie")
                if (!cookieHeader.isNullOrBlank() && cookieHeader.contains("MUSIC_U=")) {
                    handleCookie(cookieHeader)
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        binding.webView.loadUrl("https://music.163.com/#/login")

        // 网易云是单页应用，页面不刷新，定时检查 Cookie
        binding.webView.postDelayed(object : Runnable {
            override fun run() {
                checkCookie()
                binding.webView.postDelayed(this, 1500)
            }
        }, 1500)
    }

    private fun checkCookie() {
        val cookie = CookieManager.getInstance().getCookie("https://music.163.com") ?: return
        if (cookie.contains("MUSIC_U=")) {
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
                val account = Get<NeteaseAccount>(Api.NETEASE_ACCOUNT) {
                    addQuery("cookie", cookie)
                }.await()
                SPConfig.saveNeteaseLogin(
                    cookie,
                    account.userId,
                    account.nickname,
                    account.avatarUrl
                )
                toast("登录成功：${account.nickname}")
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
