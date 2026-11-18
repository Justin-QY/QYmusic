package com.qymusic.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import android.widget.TextView
import androidx.core.content.pm.PackageInfoCompat
import androidx.appcompat.app.AppCompatDelegate
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.drake.brv.utils.BRV
import com.drake.engine.base.Engine
import com.drake.net.NetConfig
import com.drake.net.exception.HttpResponseException
import com.drake.net.exception.NetConnectException
import com.drake.net.exception.NetSocketTimeoutException
import com.drake.net.exception.NetworkingException
import com.drake.net.interceptor.LogRecordInterceptor
import com.drake.net.interceptor.RequestInterceptor
import com.drake.net.interfaces.NetErrorHandler
import com.drake.net.okhttp.setConverter
import com.drake.net.okhttp.setDebug
import com.drake.net.okhttp.setDialogFactory
import com.drake.net.okhttp.setRequestInterceptor
import com.drake.net.request.BaseRequest
import com.drake.statelayout.StateConfig
import com.drake.tooltip.ToastConfig
import com.drake.tooltip.dialog.BubbleDialog
import com.drake.tooltip.toast
import com.scwang.smart.refresh.footer.ClassicsFooter
import com.scwang.smart.refresh.header.MaterialHeader
import com.scwang.smart.refresh.layout.SmartRefreshLayout
import okhttp3.Cache
import org.litepal.LitePal
import com.qymusic.app.config.Api
import com.qymusic.app.utils.CrashHandler
import com.qymusic.app.utils.DatabaseMigration
import com.qymusic.app.utils.converter.SerializationConverter
import java.util.concurrent.TimeUnit

@UnstableApi
class QYMusicApplication : Application() {

  companion object {
    @SuppressLint("StaticFieldLeak")
    lateinit var context: Context

    lateinit var simpleCache: SimpleCache
  }

  override fun onCreate() {
    super.onCreate()
    context = this

    // 应用用户选择的主题（-1 跟随系统 / 1 浅色 / 2 深色）
    AppCompatDelegate.setDefaultNightMode(com.qymusic.app.config.SPConfig.themeMode())

    CrashHandler.getInstance().init()

    val cacheSize = 2028L * 1024 * 1024 // 2GB
    val evictor = LeastRecentlyUsedCacheEvictor(cacheSize)
    val databaseProvider = StandaloneDatabaseProvider(this)
    simpleCache = SimpleCache(java.io.File(cacheDir, "media_cache"), evictor, databaseProvider)

    // 安全检查
    // com.qymusic.app.utils.SecurityManager.check(this)

    BRV.modelId = BR.m
    Engine.initialize(this)
    ToastConfig.initialize(this)
    LitePal.initialize(this)
    // 兜底迁移：补齐历史数据库缺失的字段（如收藏表 source），避免查询直接抛错
    DatabaseMigration.ensureSchema()
    // 网络请求配置
    initNetConfig()
    // StateLayout 配置
    initStateConfig()
    // 刷新视图
    initSmartRefreshLayout()
  }

  /** 网络请求配置 */
  private fun initNetConfig() {
    val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
    val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
    // Net 初始化
    NetConfig.initialize(Api.BASE_API, this) {
      // 连接超时时间
      connectTimeout(20, TimeUnit.SECONDS)
      // 读取超时时间
      readTimeout(30, TimeUnit.SECONDS)
      // 写入超时时间
      writeTimeout(30, TimeUnit.SECONDS)
      setDebug(BuildConfig.DEBUG)
      // 默认不开启缓存
      cache(Cache(cacheDir, 1024 * 1024 * 128))
      // setConverter(GsonConverter())
      // 设置响应转换器，自动进行序列化和反序列化
      setConverter(SerializationConverter())
      // 添加日志拦截器
      addInterceptor(LogRecordInterceptor(BuildConfig.DEBUG))
      // addInterceptor(RefreshTokenInterceptor())
      // 设置请求拦截器
      setRequestInterceptor(
        object : RequestInterceptor {
          override fun interceptor(request: BaseRequest) {
            // app 版本加入到请求头中
            request.addHeader("Version", versionCode.toString())
            // 平台信息
            request.addHeader("Platform", "android")

            // 安全请求头
            val timestamp = System.currentTimeMillis().toString()
            request.addHeader("Timestamp", timestamp)
            request.addHeader("Sign", getSign(timestamp, versionCode.toString()))

            // 统一处理用户凭证
            /*UserUtils.getToken()?.let {
              request.addHeader("Authorization", it)
            }*/
          }
        }
      )

      // 配置全局加载 loading 效果
      setDialogFactory {
        BubbleDialog(it).apply {
          setCancelable(false)
          setCanceledOnTouchOutside(false)
        }
      }
    }

    // 兜底：drake 自带的英文异常统一转成中文提示
    NetConfig.errorHandler = object : NetErrorHandler {
      override fun onError(e: Throwable) {
        val message = e.message.orEmpty()
        if (message.contains("unexpected error occurred in the converter", ignoreCase = true)) {
          toast("数据解析失败，请稍后重试")
        } else {
          NetErrorHandler.DEFAULT.onError(e)
        }
      }

      override fun onStateError(e: Throwable, view: View) {
        NetErrorHandler.DEFAULT.onStateError(e, view)
      }
    }
  }

  /** StateLayout 全局配置 */
  private fun initStateConfig() {
    // StateLayout 初始化
    StateConfig.apply {
      loadingLayout = R.layout.layout_loading
      errorLayout = R.layout.layout_error
      emptyLayout = R.layout.layout_empty
      // 设置重试id
      setRetryIds(R.id.error_msg)
      onError { error ->
        startAnimation()
        findViewById<TextView>(R.id.error_msg).text = handleNetworkStatus(error)
      }
      onEmpty { tag ->
        if (tag is String) {
          findViewById<TextView>(R.id.empty_tips).text = tag
        }
        startAnimation()
      }
      onContent { startAnimation() }
      onLoading { startAnimation() }
    }
  }

  /** 处理网络状态 */
  private fun View.handleNetworkStatus(error: Any?): String {
    return when (error) {
      is NetworkingException -> getString(com.drake.net.R.string.net_error)
      is NetConnectException -> getString(com.drake.net.R.string.net_connect_error)
      is NetSocketTimeoutException -> getString(com.drake.net.R.string.net_connect_timeout_error)
      is HttpResponseException -> {
        when (error.response.code) {
          200 -> {
            if (tag == "401") {
              getString(R.string.please_login)
            } else (error.message ?: getString(com.drake.net.R.string.net_other_error))
          }

          401 -> {
            // UserUtils.exitLogin()
            getString(R.string.please_login)
          }

          500 -> getString(com.drake.net.R.string.net_server_error)
          else -> getString(R.string.error_tips)
        }
      }

      is RuntimeException -> error.message ?: getString(R.string.error_tips)
      else -> getString(R.string.error_tips)
    } + ", 点击刷新重试"
  }

  /** 配置刷新的 Header 和 Footer */
  private fun initSmartRefreshLayout() {
    SmartRefreshLayout.setDefaultRefreshHeaderCreator { _, _ -> MaterialHeader(this) }
    SmartRefreshLayout.setDefaultRefreshFooterCreator { _, _ -> ClassicsFooter(this) }
  }

  private fun View.startAnimation() {
    // 先将视图隐藏然后在800毫秒内渐变显示视图
    animate().setDuration(0).alpha(0F).withEndAction { animate().setDuration(800).alpha(1F) }
  }

  private external fun getSign(timestamp: String, version: String): String

  init {
    System.loadLibrary("qymusic")
  }
}
