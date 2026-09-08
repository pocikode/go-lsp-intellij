package com.github.pocikode.go_lsp_intellij

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonStreamParser

internal data class GoModuleVersion(val path: String, val version: String)

internal data class GoModuleDependency(
    val path: String,
    val version: String,
    val main: Boolean,
    val indirect: Boolean,
    val replacement: GoModuleVersion?,
    val update: GoModuleVersion?,
    val deprecated: String?,
    val retracted: List<String>,
) {
    val effectiveVersion: String get() = replacement?.version?.takeIf(String::isNotEmpty) ?: version

    val status: String
        get() = buildList {
            if (indirect) add("indirect")
            if (replacement != null) add("replaced")
            if (update != null) add("update available")
            if (deprecated != null) add("deprecated")
            if (retracted.isNotEmpty()) add("retracted")
        }.joinToString(", ").ifEmpty { "current" }
}

internal data class GoModuleGraphEdge(val from: GoModuleVersion, val to: GoModuleVersion)

internal data class GoVulnerability(
    val id: String,
    val aliases: List<String>,
    val summary: String,
    val modulePath: String,
    val foundVersion: String,
    val fixedVersion: String?,
    val called: Boolean,
)

internal object GoDependencyParsers {
    fun modules(json: String): List<GoModuleDependency> = JsonStreamParser(json.reader()).asSequence()
        .mapNotNull { it.asObjectOrNull() }
        .mapNotNull(::module)
        .toList()

    fun graph(text: String): List<GoModuleGraphEdge> = text.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size != 2) return@mapNotNull null
        GoModuleGraphEdge(moduleVersion(parts[0]), moduleVersion(parts[1]))
    }.toList()

    fun vulnerabilities(json: String): List<GoVulnerability> {
        val advisories = linkedMapOf<String, JsonObject>()
        val findings = mutableListOf<JsonObject>()
        JsonStreamParser(json.reader()).asSequence().forEach { element ->
            val message = element.asObjectOrNull() ?: return@forEach
            message.get("osv")?.takeIf(JsonElement::isJsonObject)?.asJsonObject?.let { osv ->
                osv.string("id")?.let { advisories[it] = osv }
            }
            message.getAsJsonObject("finding")?.let(findings::add)
        }

        val byModule = linkedMapOf<Pair<String, String>, GoVulnerability>()
        findings.forEach { finding ->
            val id = finding.string("osv") ?: return@forEach
            val osv = advisories[id]
            val traces = finding.getAsJsonArray("trace")?.mapNotNull { it.asObjectOrNull() }.orEmpty()
            val trace = traces.firstOrNull()
            val modulePath = trace?.string("module") ?: "unknown module"
            val foundVersion = trace?.string("version").orEmpty()
            val fixedVersion = finding.string("fixed_version")?.takeIf(String::isNotBlank)
            val called = traces.any { it.string("function") != null }
            val value = GoVulnerability(
                id = id,
                aliases = osv?.stringList("aliases").orEmpty(),
                summary = osv?.string("summary").orEmpty(),
                modulePath = modulePath,
                foundVersion = foundVersion,
                fixedVersion = fixedVersion,
                called = called,
            )
            val key = id to modulePath
            val previous = byModule[key]
            byModule[key] = if (previous == null || (!previous.called && called)) value else previous
        }
        return byModule.values.toList()
    }

    fun workspaceDirectories(json: String, workDirectory: java.nio.file.Path): List<java.nio.file.Path> {
        val root = JsonStreamParser(json.reader()).asSequence().firstOrNull()?.asObjectOrNull() ?: return emptyList()
        return root.getAsJsonArray("Use")?.mapNotNull { use ->
            val diskPath = use.asObjectOrNull()?.string("DiskPath") ?: return@mapNotNull null
            workDirectory.resolve(diskPath).normalize()
        }.orEmpty()
    }

    private fun module(json: JsonObject): GoModuleDependency? {
        val path = json.string("Path") ?: return null
        val error = json.getAsJsonObject("Error")?.string("Err")
        if (error != null) return GoModuleDependency(path, "", false, false, null, null, error, emptyList())
        return GoModuleDependency(
            path = path,
            version = json.string("Version").orEmpty(),
            main = json.boolean("Main"),
            indirect = json.boolean("Indirect"),
            replacement = json.getAsJsonObject("Replace")?.let(::moduleVersion),
            update = json.getAsJsonObject("Update")?.let(::moduleVersion),
            deprecated = json.string("Deprecated"),
            retracted = json.stringList("Retracted"),
        )
    }

    private fun moduleVersion(json: JsonObject): GoModuleVersion? {
        val path = json.string("Path") ?: return null
        return GoModuleVersion(path, json.string("Version").orEmpty())
    }

    private fun moduleVersion(value: String): GoModuleVersion {
        val separator = value.lastIndexOf('@')
        return if (separator > 0) {
            GoModuleVersion(value.substring(0, separator), value.substring(separator + 1))
        } else {
            GoModuleVersion(value, "")
        }
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? = takeIf(JsonElement::isJsonObject)?.asJsonObject

    private fun JsonObject.string(name: String): String? = get(name)
        ?.takeUnless(JsonElement::isJsonNull)
        ?.takeIf(JsonElement::isJsonPrimitive)
        ?.asString

    private fun JsonObject.boolean(name: String): Boolean = get(name)
        ?.takeUnless(JsonElement::isJsonNull)
        ?.takeIf(JsonElement::isJsonPrimitive)
        ?.asBoolean == true

    private fun JsonObject.stringList(name: String): List<String> = getAsJsonArray(name)
        ?.mapNotNull { it.takeIf(JsonElement::isJsonPrimitive)?.asString }
        .orEmpty()
}
