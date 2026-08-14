package com.dsh.idebridge.transport

// ── 请求（与设计文档 §5.1 wire 协议一一对应） ──────────────────────────────
//
// 用平台自带的 Gson 序列化。注意 Gson 反序列化不走构造器，
// 因此响应类的字段一律可空或用基本类型，不要依赖 Kotlin 默认值。

data class CodeContextPayload(
    val version: Int = 1,
    val client: ClientInfo = ClientInfo(),
    val project: ProjectInfo? = null,
    val file: FileInfo? = null,
    val selection: SelectionInfo,
    val symbol: SymbolInfo? = null,
    val code: String,
    val question: String? = null,
)

data class ClientInfo(
    val name: String = "dsh-idea-bridge",
    val version: String = "0.1.0",
)

data class ProjectInfo(
    val name: String? = null,
    val basePath: String? = null,
)

data class FileInfo(
    val path: String? = null,
    val language: String? = null,
)

data class SelectionInfo(
    val kind: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
)

data class SymbolInfo(
    val name: String? = null,
    val qualifiedName: String? = null,
    val kind: String? = null,
)

// ── 响应 ──────────────────────────────────────────────────────────────────

data class SendResponse(
    val ok: Boolean = false,
    val status: String? = null,
    val draftId: String? = null,
    val code: String? = null,
    val message: String? = null,
)

data class HealthResponse(
    val ok: Boolean = false,
    val plugin: String? = null,
    val version: String? = null,
    val pendingDrafts: Int? = null,
)
