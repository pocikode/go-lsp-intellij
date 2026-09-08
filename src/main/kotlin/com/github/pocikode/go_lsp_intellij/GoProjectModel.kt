package com.github.pocikode.go_lsp_intellij

import java.nio.file.Path

/** The build context that makes a package meaningful to Go. */
internal data class GoBuildContext(
    val tags: List<String> = emptyList(),
    val goos: String? = null,
    val goarch: String? = null,
    val cgoEnabled: Boolean? = null,
    val vendoring: Boolean = false,
    val gopath: List<Path> = emptyList(),
)

internal data class GoSdk(
    val home: Path,
    val version: String,
    val goExecutable: Path,
    val sourceRoots: List<Path>,
)

internal data class GoPackage(
    val importPath: String,
    val directory: Path,
    val name: String,
    val modulePath: String?,
    val standard: Boolean,
    val goFiles: List<Path> = emptyList(),
    val testGoFiles: List<Path> = emptyList(),
)

internal data class GoModule(
    val path: String,
    val directory: Path,
    val goVersion: String?,
    val dependencies: List<GoModuleDependency> = emptyList(),
    val packages: List<GoPackage> = emptyList(),
)

internal data class GoExternalLibrary(
    val name: String,
    val roots: List<Path>,
    val version: String? = null,
    val kind: Kind,
) {
    enum class Kind { MODULE_CACHE, SDK, GOPATH }
}

internal data class GoProjectModel(
    val sdk: GoSdk? = null,
    val modules: List<GoModule> = emptyList(),
    val packages: List<GoPackage> = emptyList(),
    val externalLibraries: List<GoExternalLibrary> = emptyList(),
    val buildContext: GoBuildContext = GoBuildContext(),
    val errors: List<String> = emptyList(),
)

internal object GoProjectModelParsers {
    fun packages(json: String): List<GoPackage> =
        com.google.gson.JsonStreamParser(json.reader()).asSequence().mapNotNull { element ->
            val value = element.takeIf(com.google.gson.JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            val directory = value.get("Dir")?.asString?.let(Path::of) ?: return@mapNotNull null
            val importPath = value.get("ImportPath")?.asString ?: return@mapNotNull null
            GoPackage(
                importPath = importPath,
                directory = directory,
                name = value.get("Name")?.asString.orEmpty(),
                modulePath = value.getAsJsonObject("Module")?.get("Path")?.asString,
                standard = value.get("Standard")?.asBoolean == true,
                goFiles = value.stringPaths("GoFiles", directory),
                testGoFiles = value.stringPaths("TestGoFiles", directory),
            )
        }.toList()

    private fun com.google.gson.JsonObject.stringPaths(name: String, directory: Path): List<Path> =
        getAsJsonArray(name)?.mapNotNull { it.takeIf(com.google.gson.JsonElement::isJsonPrimitive)?.asString }
            ?.map(directory::resolve).orEmpty()
}
