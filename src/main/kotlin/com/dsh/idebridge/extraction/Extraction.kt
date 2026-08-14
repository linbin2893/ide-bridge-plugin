package com.dsh.idebridge.extraction

/** 提取结果：代码文本 + 全部元数据（与 wire 协议一一对应）。 */
data class Extraction(
    // "selection" | "class"（Java 整类）| "declaration"（其他语言的具名声明）
    val kind: String,
    // 仅选区时非空；无选区只发引用（路径 + 名字），AI 按绝对路径自行读取文件
    val code: String,
    // 无选区时为 null（草稿不显示行号）
    val startLine: Int?,
    val endLine: Int?,
    val symbolName: String?,
    val symbolQualifiedName: String?,
    // "class"（Java）| "declaration"（其他语言）| null
    val symbolKind: String?,
    val language: String,
    val filePath: String,
    val projectName: String,
    val basePath: String,
)

/** 发送前预览用的一行摘要。 */
fun Extraction.previewSummary(): String =
    "$projectName · $filePath" +
        (startLine?.let { " · 第 $it–${endLine ?: it} 行" } ?: "") +
        (symbolName?.let { " · $it" } ?: "") +
        (if (code.isEmpty()) " · 仅引用（不附代码）" else "")
