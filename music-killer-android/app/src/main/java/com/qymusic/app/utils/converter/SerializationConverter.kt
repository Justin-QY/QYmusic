@file:Suppress("UNCHECKED_CAST", "MemberVisibilityCanBePrivate")

package com.qymusic.app.utils.converter

import com.drake.net.Net
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
import okhttp3.OkHttpClient
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
      return NetConverter.onConvert<R>(succeed, response)
    } catch (e: ConvertException) {
      val code = response.code
      when {
        code in 200..299 -> { // 请求成功
          val bodyString = response.body?.string() ?: return null
          val kType = response.request.kType
            ?: throw ConvertException(response, "请求缺少类型信息")
          return try {
            val json = JSONObject(bodyString) // 获取JSON中后端定义的错误码和错误信息
            when (val srvCode = json.getString(this.code)) {
              success -> {
                if (!json.has("data")) {
                  return null
                }
                // data 是字符串时 getString 已经去掉引号，再走 JSON 解析会失败
                if (succeed == String::class.java) {
                  return json.getString("data") as R
                }
                parseBody<R>(response, bodyString = json.getString("data"), kType = kType)
              }

              "401" -> {
                val errorMessage = json.optString(
                  message,
                  NetConfig.app.getString(com.drake.net.R.string.no_error_message)
                )
                throw ResponseException(response, errorMessage, tag = srvCode)
              }

              else -> {
                val errorMessage = json.optString(
                  message,
                  NetConfig.app.getString(com.drake.net.R.string.no_error_message)
                )
                throw ResponseException(response, errorMessage, tag = srvCode) // 将业务错误码作为tag传递
              }
            }
          } catch (_: JSONException) { // 固定格式JSON分析失败直接解析JSON
            parseBody<R>(response, bodyString, kType)
          }
        }

        code in 400..499 -> throw RequestParamsException(response, code.toString()) // 请求参数错误
        code >= 500 -> throw ServerResponseException(response, code.toString()) // 服务器异常错误
        else -> throw ConvertException(response, message = "Http status code not within range")
      }
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