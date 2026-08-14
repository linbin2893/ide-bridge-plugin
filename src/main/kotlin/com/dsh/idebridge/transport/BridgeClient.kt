package com.dsh.idebridge.transport

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

sealed class SendOutcome {
    data class Accepted(val draftId: String) : SendOutcome()
    data class Rejected(val httpStatus: Int, val code: String?, val message: String) : SendOutcome()

    /** 请求根本没走到「服务端给出答复」这一步：地址非法、连不上、超时、序列化失败。 */
    data class Failed(val reason: String) : SendOutcome()
}

class HealthOutcome(
    val ok: Boolean,
    val pendingDrafts: Int,
    val message: String,
)

object BridgeClient {

    private val log = Logger.getInstance(BridgeClient::class.java)

    private val gson = Gson()

    // 必须钉死 HTTP/1.1：JDK HttpClient 默认版本是 HTTP_2，对明文 http:// 会先发一个带
    // `Upgrade: h2c` 的请求做协议协商。Node 的 http server 见到 Upgrade 头就不再走正常
    // 响应路径（转 'upgrade' 事件），DSH 侧没有对应处理便直接销毁 socket，一个字节都不回，
    // 客户端于是抛 IOException("HTTP/1.1 header parser received no bytes")——表象是「连不上」，
    // 实则 TCP 早已连通。DSH 只提供 HTTP/1.1，无需协商。
    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    /** POST /ide/context；返回 Accepted / Rejected（带 HTTP 状态与错误码）/ Failed（带具体原因）。 */
    fun send(baseUrl: String, token: String?, payload: CodeContextPayload): SendOutcome {
        val uri = parseUri(baseUrl, "/ide/context")
            ?: return SendOutcome.Failed("服务地址格式不正确：$baseUrl")

        val body = try {
            gson.toJson(payload)
        } catch (e: Exception) {
            log.warn("序列化请求体失败", e)
            return SendOutcome.Failed("请求体序列化失败：${describe(e)}")
        }

        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (!token.isNullOrBlank()) builder.header("X-DSH-IDE-Token", token)

        val response = try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            log.warn("POST $uri 失败", e)
            return SendOutcome.Failed("无法连接 DSH（$baseUrl）：${describe(e)}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return SendOutcome.Failed("请求被中断")
        }

        val text = response.body().orEmpty()
        val parsed = try {
            gson.fromJson(text, SendResponse::class.java)
        } catch (e: JsonSyntaxException) {
            log.warn("POST $uri 响应不是合法 JSON：${text.take(500)}", e)
            null
        }
        return when {
            parsed != null && parsed.ok -> SendOutcome.Accepted(parsed.draftId.orEmpty())
            parsed != null -> SendOutcome.Rejected(response.statusCode(), parsed.code, parsed.message.orEmpty())
            else -> SendOutcome.Rejected(response.statusCode(), null, text.ifBlank { "空响应" }.take(500))
        }
    }

    /** GET /ide/health；供设置页「测试连接」使用。必须在后台线程调用。 */
    fun health(baseUrl: String): HealthOutcome {
        val uri = parseUri(baseUrl, "/ide/health")
            ?: return HealthOutcome(false, 0, "服务地址格式不正确：$baseUrl")

        return try {
            val response = client.send(
                HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val parsed = try {
                gson.fromJson(response.body().orEmpty(), HealthResponse::class.java)
            } catch (e: JsonSyntaxException) {
                log.warn("GET $uri 响应不是合法 JSON", e)
                null
            }
            if (parsed != null && parsed.ok) {
                val pending = parsed.pendingDrafts ?: 0
                HealthOutcome(true, pending, "DSH 连接正常，待取草稿 $pending 条")
            } else {
                HealthOutcome(
                    false, 0,
                    "DSH 返回异常（HTTP ${response.statusCode()}）：${response.body().orEmpty().take(200)}",
                )
            }
        } catch (e: IOException) {
            log.warn("GET $uri 失败", e)
            HealthOutcome(false, 0, "无法连接 DSH：${describe(e)}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            HealthOutcome(false, 0, "请求被中断")
        }
    }

    private fun parseUri(baseUrl: String, path: String): URI? {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        return try {
            val uri = URI.create(trimmed + path)
            if (uri.scheme == null || uri.host == null) null else uri
        } catch (e: IllegalArgumentException) {
            log.warn("服务地址非法：$baseUrl", e)
            null
        }
    }

    private fun describe(e: Exception): String = e.message ?: e.javaClass.simpleName
}
