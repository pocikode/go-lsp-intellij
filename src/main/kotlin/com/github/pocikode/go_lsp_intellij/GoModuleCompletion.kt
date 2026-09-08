package com.github.pocikode.go_lsp_intellij

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.DumbAware

internal data class GoModuleSuggestion(val value: String, val typeText: String)

internal object GoModuleCompletion {
    private val goModDirectives = listOf("module", "go", "toolchain", "godebug", "require", "exclude", "replace", "retract", "tool")
    private val moduleDirectives = setOf("require", "exclude", "replace")

    fun suggestions(
        fileName: String,
        text: String,
        offset: Int,
        modules: List<GoModuleDependency>,
    ): List<GoModuleSuggestion> {
        if (fileName !in setOf("go.mod", "go.sum")) return emptyList()
        val safeOffset = offset.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', safeOffset - 1).let { if (it < 0) 0 else it + 1 }
        val beforeCaret = text.substring(lineStart, safeOffset)
        val words = beforeCaret.trimStart().split(Regex("\\s+")).filter(String::isNotEmpty)
        val afterWhitespace = beforeCaret.lastOrNull()?.isWhitespace() == true

        if (fileName == "go.mod" && enclosingBlockDirective(text.substring(0, lineStart)) == null &&
            (words.isEmpty() || (words.size == 1 && !afterWhitespace))
        ) {
            return goModDirectives.map { GoModuleSuggestion(it, "directive") }
        }

        val explicitDirective = words.firstOrNull()?.takeIf { it in goModDirectives }
        val directive = explicitDirective ?: enclosingBlockDirective(text.substring(0, lineStart))
        val operands = if (explicitDirective == null) words else words.drop(1)
        val moduleCandidates = modules.filterNot(GoModuleDependency::main).distinctBy(GoModuleDependency::path)
        if (fileName == "go.sum" || directive in moduleDirectives) {
            val arrow = operands.indexOf("=>")
            val activeOperands = if (directive == "replace" && arrow >= 0) operands.drop(arrow + 1) else operands
            val completingModule = if (fileName == "go.sum") {
                words.isEmpty() || (words.size == 1 && !afterWhitespace)
            } else {
                activeOperands.isEmpty() || (activeOperands.size == 1 && !afterWhitespace)
            }
            if (completingModule) {
                return moduleCandidates.map { GoModuleSuggestion(it.path, it.status) }
            }
            val modulePath = if (fileName == "go.sum") words.firstOrNull() else activeOperands.firstOrNull()
            return moduleCandidates.firstOrNull { it.path == modulePath }?.let { module ->
                listOfNotNull(module.version, module.update?.version)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .map { GoModuleSuggestion(it, if (it == module.update?.version) "available" else "selected") }
            }.orEmpty()
        }
        return emptyList()
    }

    fun modulesFromFile(fileName: String, text: String): List<GoModuleDependency> {
        val result = linkedMapOf<Pair<String, String>, GoModuleDependency>()
        var blockDirective: String? = null
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore("//").trim()
            if (line.isEmpty()) return@forEach
            val words = line.split(Regex("\\s+")).filter(String::isNotEmpty)
            val first = words.firstOrNull() ?: return@forEach
            if (line.endsWith("(") && first in moduleDirectives) {
                blockDirective = first
                return@forEach
            }
            if (first == ")") {
                blockDirective = null
                return@forEach
            }
            val directive = blockDirective ?: first.takeIf { it in moduleDirectives }
            val operands = if (blockDirective != null) words else words.drop(1)
            val modulePath: String
            val version: String
            when {
                fileName.endsWith(".sum") -> {
                    modulePath = words.getOrNull(0) ?: return@forEach
                    version = words.getOrNull(1)?.removeSuffix("/go.mod") ?: return@forEach
                }
                directive in moduleDirectives -> {
                    modulePath = operands.getOrNull(0)?.trim('"', '`') ?: return@forEach
                    version = operands.getOrNull(1)?.takeIf { it != "=>" }.orEmpty()
                }
                else -> return@forEach
            }
            result[modulePath to version] = GoModuleDependency(
                modulePath, version, false, directive == "require" && rawLine.contains("// indirect"),
                null, null, null, emptyList(),
            )
        }
        return result.values.toList()
    }

    private fun enclosingBlockDirective(textBeforeLine: String): String? {
        var block: String? = null
        textBeforeLine.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore("//").trim()
            val first = line.split(Regex("\\s+")).firstOrNull()
            if (line.endsWith("(") && first in moduleDirectives) block = first
            if (line == ")") block = null
        }
        return block
    }
}

/** Local completion for files for which gopls intentionally returns no completion items. */
class GoModuleCompletionContributor : CompletionContributor(), DumbAware {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile.virtualFile ?: return
        if (file.name !in setOf("go.mod", "go.sum") || GoLspSupport.isNativeGoPluginLoaded()) return
        val report = GoDependencyService.getInstance(parameters.originalFile.project).report
        val text = parameters.editor.document.text
        val modules = (report.modules + GoModuleCompletion.modulesFromFile(file.name, text))
            .distinctBy { it.path to it.version }
        val suggestions = GoModuleCompletion.suggestions(file.name, text, parameters.offset, modules)
        suggestions.forEach { suggestion ->
            result.addElement(LookupElementBuilder.create(suggestion.value).withTypeText(suggestion.typeText, true))
        }
    }
}
