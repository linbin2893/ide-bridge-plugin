package com.dsh.idebridge.extraction

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiQualifiedNamedElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * 提取规则（设计文档决策 D1/D1'）：
 * - 有选区 → 选区文本（kind=selection），尝试解析选区起点所在声明作为 symbol 元数据
 * - 无选区 → 只发光标所在声明的引用（kind=class/declaration，路径 + 名字，不带代码）；找不到返回 null
 *
 * Java 走 [PsiClass] 精确路径，可拿到全限定名；其他语言（Kotlin 等）的 PSI 不实现
 * [PsiClass]，退回平台通用的 [PsiNameIdentifierOwner]，此时取到的可能是类也可能是函数。
 */
object SelectionExtractor {

    /** 光标所在的具名声明及其元数据。 */
    private class Declaration(
        val element: PsiElement,
        val name: String?,
        val qualifiedName: String?,
        val kind: String,
    )

    fun extract(project: Project, editor: Editor, file: PsiFile?): Extraction? {
        val document: Document = editor.document
        val selection = editor.selectionModel

        // 1) 有选区优先
        if (selection.hasSelection()) {
            val start = selection.selectionStart
            val end = selection.selectionEnd
            val text = document.getText(TextRange(start, end))
            if (text.isBlank()) return null
            val declaration = file?.findElementAt(start)?.let { findDeclaration(it) }
            return Extraction(
                kind = "selection",
                code = text,
                startLine = document.getLineNumber(start) + 1,
                endLine = document.getLineNumber(end) + 1,
                symbolName = declaration?.name,
                symbolQualifiedName = declaration?.qualifiedName,
                symbolKind = declaration?.kind,
                language = file?.language?.displayName ?: "",
                filePath = relativePath(project, file),
                projectName = project.name,
                basePath = project.basePath ?: "",
            )
        }

        // 2) 无选区 → 只发类/声明的引用（路径 + 名字），不附代码：
        //    草稿元数据里已有绝对路径，AI 可直接按路径读取文件
        val caret = editor.caretModel.primaryCaret.offset
        val element = file?.findElementAt(caret) ?: return null
        val declaration = findDeclaration(element) ?: return null
        return Extraction(
            // Java 仍发 "class"，保持与既有 wire 协议一致；其他语言才用新值 "declaration"
            kind = declaration.kind,
            code = "",
            startLine = null,
            endLine = null,
            symbolName = declaration.name,
            symbolQualifiedName = declaration.qualifiedName,
            symbolKind = declaration.kind,
            language = file.language.displayName,
            filePath = relativePath(project, file),
            projectName = project.name,
            basePath = project.basePath ?: "",
        )
    }

    private fun findDeclaration(element: PsiElement): Declaration? {
        // Java：取所在类，能拿到全限定名
        PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)?.let { psiClass ->
            return Declaration(psiClass, psiClass.name, psiClass.qualifiedName, "class")
        }
        // 其他语言：退回通用具名声明；命中整个文件时视为没找到，避免把全文当成一个声明发出去
        val named = PsiTreeUtil.getParentOfType(element, PsiNameIdentifierOwner::class.java, false)
        if (named == null || named is PsiFile || named.name.isNullOrBlank()) return null
        return Declaration(
            named,
            named.name,
            (named as? PsiQualifiedNamedElement)?.qualifiedName,
            "declaration",
        )
    }

    private fun relativePath(project: Project, file: PsiFile?): String {
        val vf = file?.virtualFile ?: return file?.name ?: ""
        val baseDir = project.guessProjectDir() ?: return vf.name
        return VfsUtilCore.getRelativePath(vf, baseDir) ?: vf.name
    }
}
