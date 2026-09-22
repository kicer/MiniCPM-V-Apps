package com.example.minicpm_v_demo

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import javax.net.ssl.SSLParameters

/**
 * llm-relay 生产端：模型加载完成后连接中转服务器
 * (wss://llm-relay.ai.foresh.com/ws)，把本机当作 OpenAI 兼容的
 * 推理后端。H5 Agent 的请求经服务器转成 `inference` 消息下发，
 * 手机将流式原始文本按 `chunk` 回传；XML 工具调用由服务器解析
 * 成标准 tool_calls，手机端不做任何解析。
 *
 * 协议（见 llm-relay README）：
 *  - 手机→服务器: register / chunk / done / error / ping
 *  - 服务器→手机: registered / inference / cancel / pong
 *
 * 上下文策略：每个 inference 请求都是**无状态**执行——先
 * clearContext()，再（如有 system/tools）setSystemPrompt()，最后把
 * 完整 OpenAI messages 历史扁平化为单条 user prompt 喂给模型。
 * 这样 H5 重试 / 多轮 agent loop / 手机重启都不需要客户端保存
 * 会话状态，代价是每轮重新 prefill 整个历史（CPU 批量解码，很快）。
 * 注意：这会让本地聊天页的模型上下文被重置，relay 使用期间
 * 建议不要在同一页面继续长对话（或手动"清除对话"）。
 */
object RelayClient {

    private const val TAG = "RelayClient"

    /** 中转服务器默认地址（HTTP 侧为 https://llm-relay.ai.foresh.com），设置后可被 relay_url 覆盖。 */
    const val RELAY_WS_URL = "wss://llm-relay.ai.foresh.com/ws"

    /** 鉴权 token 配置：服务器白名单模式下 register 携带；空 = 不发（兼容 auth.enabled:false）。 */
    private const val RELAY_PREFS = "relay_prefs"
    private const val KEY_RELAY_URL = "relay_url"
    private const val KEY_RELAY_TOKEN = "relay_token"

    /** 应用层心跳：服务器按消息计时，超过 idle_timeout_ms 判离线。 */
    private const val PING_INTERVAL_MS = 10_000L
    private const val RECONNECT_MIN_MS = 2_000L
    private const val RECONNECT_MAX_MS = 30_000L

    /** chunk 缓冲阈值：攒够这些字符（或生成结束）再发一帧，减少 WS 噪声。 */
    private const val CHUNK_FLUSH_CHARS = 96

    enum class Status { Disconnected, Connecting, Connected, Reconnecting }

    private val _status = MutableStateFlow(Status.Disconnected)
    val status: StateFlow<Status> = _status.asStateFlow()

    private fun prefs(context: Context) =
        context.getSharedPreferences(RELAY_PREFS, Context.MODE_PRIVATE)

    /** 实际生效的中转地址：设置值优先，留空 = 用内置默认地址。 */
    fun relayUrl(context: Context): String =
        prefs(context).getString(KEY_RELAY_URL, null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: RELAY_WS_URL

    /** 设置里的鉴权 token；空白返回 null（register 不带 token，兼容未开鉴权的服务器）。 */
    fun relayToken(context: Context): String? =
        prefs(context).getString(KEY_RELAY_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }

    /** 保存用户在中转设置弹窗里改的 url/token；调用方需 stop() + start() 使其生效。 */
    fun saveConfig(context: Context, url: String, token: String) {
        prefs(context).edit()
            .putString(KEY_RELAY_URL, url.trim())
            .putString(KEY_RELAY_TOKEN, token.trim())
            .apply()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var appContext: Context? = null
    private var engine: LlamaEngine? = null

    @Volatile
    private var socket: WebSocketClient? = null

    @Volatile
    private var running = false

    /** 每次 (重)启动 / 换模型自增，用于丢弃过期连接回调与重连计划。 */
    @Volatile
    private var generation = 0

    @Volatile
    private var registeredModelName: String? = null

    /** 当前连接所用的地址，用于检测设置里改了 URL 后自动重连。 */
    @Volatile
    private var activeUrl: String? = null

    @Volatile
    private var reconnectDelayMs = RECONNECT_MIN_MS

    @Volatile
    private var everRegistered = false

    /** 当前正在生成中的 request_id（服务器保证同一设备串行派发）。 */
    @Volatile
    private var inflightRequestId: String? = null

    /** 心跳协程句柄：重连后重注册时先取消旧循环，避免 ping 叠加。 */
    @Volatile
    private var heartbeatJob: Job? = null

    // ---------------------------------------------------------------- 生命周期

    /**
     * 幂等启动。模型加载完成（[LlamaState.ModelReady]）后调用；
     * 选中的模型或中转设置（url/token）发生变化时会自动断开重连并
     * 以新模型名 / 新 token 重新注册。
     */
    @Synchronized
    fun start(context: Context, engine: LlamaEngine) {
        appContext = context.applicationContext
        this.engine = engine
        val modelName = relayModelName(context)
        val url = relayUrl(context)

        if (running && (registeredModelName != modelName || activeUrl != url)) {
            // 模型 / 中转地址变更：关掉旧连接，重新注册
            shutDownSocket()
            running = false
            registeredModelName = null
        }
        if (running) return
        running = true
        registeredModelName = modelName
        activeUrl = url
        generation++
        reconnectDelayMs = RECONNECT_MIN_MS
        connectOnce(generation)
    }

    /** 卸载模型 / Activity 销毁时调用，彻底断开（不再自动重连）。 */
    @Synchronized
    fun stop() {
        running = false
        generation++
        registeredModelName = null
        activeUrl = null
        everRegistered = false
        if (inflightRequestId != null) {
            engine?.cancelGeneration()
        }
        shutDownSocket()
        _status.value = Status.Disconnected
        Log.i(TAG, "Relay client stopped")
    }

    private fun shutDownSocket() {
        val s = socket
        socket = null
        try {
            s?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing relay socket", e)
        }
    }

    // ---------------------------------------------------------------- 连接管理

    private fun connectOnce(gen: Int) {
        val ctx = appContext ?: return
        val url = activeUrl ?: RELAY_WS_URL
        _status.value = if (everRegistered) Status.Reconnecting else Status.Connecting

        val client = try {
            object : WebSocketClient(URI(url)) {
                init {
                    // 库级 TCP 保活探测；应用层另有 {"type":"ping"} 心跳。
                    connectionLostTimeout = 45
                }

                override fun onSetSSLParameters(sslParameters: SSLParameters?) {
                    // 强制 HTTPS 域名校验，防中间人替换 wss 证书。
                    sslParameters?.endpointIdentificationAlgorithm = "HTTPS"
                }

                override fun onOpen(handshakedata: ServerHandshake?) {
                    if (gen != generation) return
                    Log.i(TAG, "Relay WS connected, registering as '$registeredModelName'")
                    val register = JSONObject()
                        .put("type", "register")
                        .put("device_id", deviceId(ctx))
                        .put("model", registeredModelName ?: "")
                    // 服务器开启 token 白名单鉴权时，未带合法 token 的连接会被直接
                    // 关闭（不返回 registered）；token 留空则兼容 auth.enabled:false。
                    relayToken(ctx)?.let { register.put("token", it) }
                    rawSend(register)
                }

                override fun onMessage(message: String?) {
                    if (gen != generation || message.isNullOrEmpty()) return
                    handleServerMessage(message)
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    if (gen != generation) return
                    Log.w(TAG, "Relay WS closed (code=$code, reason=$reason, remote=$remote)")
                    scheduleReconnect(gen)
                }

                override fun onError(ex: Exception?) {
                    if (gen != generation) return
                    Log.w(TAG, "Relay WS error", ex)
                    // Java-WebSocket 会在 error 后继续回调 onClose，重连由 onClose 统一触发。
                }
            }
        } catch (e: Exception) {
            // 用户在设置里填了非法 URL 等：不崩溃，按重连节奏等待下次 start()。
            Log.e(TAG, "Invalid relay URL '$url'", e)
            _status.value = Status.Disconnected
            return
        }

        socket = client
        try {
            client.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate relay connection", e)
            scheduleReconnect(gen)
        }
    }

    private fun scheduleReconnect(gen: Int) {
        if (!running || gen != generation) return
        _status.value = Status.Reconnecting
        val waitMs = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(RECONNECT_MAX_MS)
        scope.launch {
            delay(waitMs)
            if (running && gen == generation) {
                Log.i(TAG, "Reconnecting to relay in place (after ${waitMs}ms backoff)")
                connectOnce(gen)
            }
        }
    }

    // ---------------------------------------------------------------- 消息路由

    private fun handleServerMessage(raw: String) {
        val msg = try {
            JSONObject(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring non-JSON relay message: ${raw.take(120)}")
            return
        }
        when (msg.optString("type")) {
            "registered" -> {
                everRegistered = true
                _status.value = Status.Connected
                reconnectDelayMs = RECONNECT_MIN_MS
                Log.i(TAG, "Registered with relay as device '${msg.optString("device_id")}'")
                startHeartbeat(generation)
            }

            "inference" -> {
                if (engine == null) {
                    sendError(msg.optString("request_id"), "engine unavailable")
                    return
                }
                scope.launch { runInference(msg) }
            }

            "cancel" -> {
                val requestId = msg.optString("request_id")
                if (requestId.isNotEmpty() && requestId == inflightRequestId) {
                    Log.i(TAG, "Relay cancel received for $requestId, aborting generation")
                    engine?.cancelGeneration()
                    // 生成循环退出后照常回 done，释放服务器侧设备忙碌标记。
                }
            }

            "pong" -> Unit // 心跳应答，仅用于保活

            else -> Log.d(TAG, "Ignoring relay message: ${msg.optString("type")}")
        }
    }

    private fun startHeartbeat(gen: Int) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && running && gen == generation) {
                delay(PING_INTERVAL_MS)
                rawSend(JSONObject().put("type", "ping"))
            }
        }
    }

    // ---------------------------------------------------------------- 推理执行

    private suspend fun runInference(msg: JSONObject) {
        val eng = engine ?: return
        val requestId = msg.optString("request_id")
        if (requestId.isEmpty()) return

        val buffer = StringBuilder()
        inflightRequestId = requestId
        try {
            // 与本地聊天共用单 llama context，串行化整轮推理。
            eng.inferenceMutex.withLock {
                if (eng.state.value !is LlamaState.ModelReady) {
                    sendError(requestId, "model not ready on device")
                    return@withLock
                }

                val messages = msg.optJSONArray("messages")
                if (messages == null || messages.length() == 0) {
                    sendError(requestId, "empty messages")
                    return@withLock
                }
                val tools = msg.optJSONArray("tools")
                val params = msg.optJSONObject("params")

                val systemPrompt = buildSystemPrompt(messages, tools)
                val userPrompt = buildUserPrompt(messages)
                if (userPrompt == null) {
                    sendError(requestId, "no usable message content")
                    return@withLock
                }
                val predictLength = params?.optInt("max_tokens", LlamaEngine.DEFAULT_PREDICT_LENGTH)
                    ?.coerceIn(1, 4096) ?: LlamaEngine.DEFAULT_PREDICT_LENGTH

                // 无状态执行：重置上下文 →（可选）system → 单条扁平 user prompt
                eng.clearContext()
                if (!systemPrompt.isNullOrBlank()) {
                    eng.setSystemPrompt(systemPrompt)
                }

                eng.sendUserPrompt(userPrompt, predictLength).collect { token ->
                    if (inflightRequestId != requestId) return@collect // 已被 stop()/cancel 接管
                    buffer.append(token)
                    if (buffer.length >= CHUNK_FLUSH_CHARS) {
                        sendChunk(requestId, buffer.toString())
                        buffer.setLength(0)
                    }
                }
                if (buffer.isNotEmpty()) {
                    sendChunk(requestId, buffer.toString())
                    buffer.setLength(0)
                }
                sendDone(requestId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Relay inference $requestId failed", e)
            if (buffer.isNotEmpty()) {
                // 尽量保留已生成的部分，再报错，便于 H5 侧观察
                sendChunk(requestId, buffer.toString())
            }
            sendError(requestId, e.message ?: e.javaClass.simpleName)
        } finally {
            if (inflightRequestId == requestId) inflightRequestId = null
        }
    }

    // ---------------------------------------------------------------- prompt 构建

    /** system 消息（多条拼接）+ tools 声明时追加的 XML 工具调用说明。 */
    private fun buildSystemPrompt(messages: JSONArray, tools: JSONArray?): String? {
        val parts = ArrayList<String>()

        val sys = StringBuilder()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") != "system") continue
            val text = contentText(m.opt("content"))
            if (text.isNotBlank()) {
                if (sys.isNotEmpty()) sys.append("\n\n")
                sys.append(text)
            }
        }
        if (sys.isNotEmpty()) parts.add(sys.toString())

        if (tools != null && tools.length() > 0) {
            parts.add(toolInstruction(tools))
        }
        return parts.joinToString("\n\n").ifBlank { null }
    }

    /**
     * MiniCPM5 官方推荐 XML 风格工具调用（对齐 SGLang `--tool-call-parser
     * minicpm5` 与中转服务器的解析规则），函数名与参数需命中请求里的
     * tools schema，否则服务器会按原始文本透传。
     */
    private fun toolInstruction(tools: JSONArray): String = buildString {
        appendLine("你可以调用下面列出的函数来完成用户的请求。可用函数（JSON Schema 格式）：")
        appendLine("<tools>")
        for (i in 0 until tools.length()) {
            val tool = tools.optJSONObject(i) ?: continue
            appendLine(tool.toString())
        }
        appendLine("</tools>")
        appendLine("需要调用函数时，严格按照以下 XML 格式输出；一次回复可以包含多个并行的函数调用：")
        appendLine("<function name=\"函数名\">")
        appendLine("  <param name=\"参数名\">参数值</param>")
        appendLine("</function>")
        append("只输出函数调用本身，不要编造函数的执行结果；当对话中给出函数的返回结果后，再基于结果继续回答。")
    }

    /**
     * 把除 system 外的完整历史扁平化为单条 user prompt：
     * 历史部分逐条标注角色，最新一条作为当前请求。
     * 单条 user 消息时直接透传，与本地聊天行为一致。
     */
    private fun buildUserPrompt(messages: JSONArray): String? {
        val chat = ArrayList<Pair<String, String>>()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role")
            if (role == "system") continue
            val text = contentText(m.opt("content"))
            if (text.isNotBlank()) chat.add(role to text)
        }
        if (chat.isEmpty()) return null

        val last = chat.last()
        val earlier = chat.dropLast(1)
        if (earlier.isEmpty()) return last.second

        return buildString {
            appendLine("以下是一段对话历史（user=用户，assistant=你此前的回复，tool=函数执行结果）：")
            appendLine("<history>")
            for ((role, text) in earlier) {
                append(role).append(": ").appendLine(text)
            }
            appendLine("</history>")
            appendLine()
            appendLine("现在请针对下面这条最新消息，以助手身份继续回复：")
            appendLine("${last.first}: ${last.second}")
        }
    }

    /** OpenAI content 兼容 string / 多模态数组两种形态；图片等非文本部分跳过。 */
    private fun contentText(content: Any?): String {
        return when (content) {
            is String -> content
            is JSONArray -> {
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    if (part.optString("type") == "text") {
                        val t = part.optString("text")
                        if (t.isNotEmpty()) {
                            if (sb.isNotEmpty()) sb.append("\n")
                            sb.append(t)
                        }
                    }
                }
                sb.toString()
            }

            else -> ""
        }
    }

    // ---------------------------------------------------------------- WS 发送

    private fun relayModelName(context: Context): String =
        LlamaEngine.getSelectedModel(context)
            .displayName
            .replace(Regex("\\s*\\(.*\\)$"), "")

    private fun deviceId(context: Context): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            null
        }
        return "android-" + (androidId ?: "unknown")
    }

    private fun rawSend(obj: JSONObject) {
        val s = socket ?: return
        if (!s.isOpen) return
        try {
            s.send(obj.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Relay send failed: ${obj.optString("type")}", e)
        }
    }

    private fun sendChunk(requestId: String, text: String) {
        if (text.isEmpty()) return
        rawSend(
            JSONObject()
                .put("type", "chunk")
                .put("request_id", requestId)
                .put("text", text)
        )
    }

    private fun sendDone(requestId: String) {
        rawSend(
            JSONObject()
                .put("type", "done")
                .put("request_id", requestId)
        )
    }

    private fun sendError(requestId: String, message: String) {
        if (requestId.isEmpty()) return
        rawSend(
            JSONObject()
                .put("type", "error")
                .put("request_id", requestId)
                .put("message", message)
        )
    }
}
