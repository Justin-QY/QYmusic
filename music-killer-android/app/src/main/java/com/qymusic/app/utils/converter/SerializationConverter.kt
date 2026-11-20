@file:Suppress("UNCHECKED_CAST", "MemberVisibilityCanBePrivate")

package com.qymusic.app.utils.converter

import com.drake.net.NetConfig
import com.drake.net.convert.NetConverter
import com.drake.net.exception.ConvertException
import com.drake.net.exception.NetException
import com.drake.net.exception.RequestParamsException
import com.drake.net.exception.ResponseException
import com.drake.net.exception.ServerResponseException
import com.drake.net.request.kType
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import com.qymusic.app.utils.json
import java.lang.reflect.Type
import kotlin.reflect.KType

/**
 * 处理请求的序列化和反序列化
 */
class SerializationConverter(
  val success: String = "200", // 成功的状态码
  val code: String = "code", // code 字段名
  val message: String = "msg", // message 字段名
) : NetConverter {

  override fun <R> onConvert(succeed: Type, response: Response): R? {
    try {
      val httpCode = response.code
      when {
        httpCode in 200..299 -> Unit
        httpCode in 400..499 -> throw RequestParamsException(response, httpCode.toString())
        httpCode >= 500 -> throw ServerResponseException(response, httpCode.toString())
        else -> throw ConvertException(response, message = "Http status code not within range")
      }

      val bodyString = response.body?.string() ?: return null
      val kType = response.request.kType
        ?: throw ConvertException(response, "请求缺少类型信息")

      // 先按业务信封 {code, msg, data} 解析。
      // 不能先交给默认转换器：字段全带默认值的模型（如二维码 QrCreate）会把
      // 整个信封当成空对象解析成功，导致 data 里的 image 变成空字符串。
      val envelope = try {
        JSONObject(bodyString)
      } catch (_: JSONException) {
        null
      }

      if (envelope == null || !envelope.has(this.code)) {
        // 非信封格式（数组 / 纯文本）直接按目标类型解析
        return parseBody<R>(response, bodyString, kType)
      }

      val srvCode = envelope.optString(this.code)
      if (srvCode != success) {
        val errorMessage = envelope.optString(
          message,
          NetConfig.app.getString(com.drake.net.R.string.no_error_message)
        )
        // 将业务错误码作为tag传递
        throw ResponseException(response, errorMessage, tag = srvCode)
      }

      if (!envelope.has("data")) {
        return null
      }
      // data 是字符串时 getString 已经去掉引号，再走 JSON 解析会失败
      if (succeed == String::class.java) {
        return envelope.getString("data") as R
      }
      return parseBody<R>(response, envelope.getString("data"), kType)
    } catch (e: NetException) {
      // 业务异常（如服务端返回的提示）原样抛出
      throw e
    } catch (e: Exception) {
      // 任何解析异常都转成中文提示，避免把 "An unexpected error occurred in the converter" 抛给用户
      throw ConvertException(response, "数据解析失败，请稍后重试", e)
    }
  }

  /**
   * 解析响应体；解析失败统一转成中文，方便用户理解。
   */
  private fun <R> parseBody(response: Response, bodyString: String, kType: KType): R? {
    return try {
      json.decodeFromString(Json.serializersModule.serializer(kType), bodyString) as R
    } catch (e: Exception) {
      throw ConvertException(response, "数据解析失败，请稍后重试", e)
    }
  }
}
