package dev.go_lsp.intellij

import com.intellij.openapi.fileTypes.LanguageFileType
import icons.GoLspIcons

object GoFileType : LanguageFileType(GoLanguage) {
    override fun getName() = "Go"
    override fun getDescription() = "Go source file"
    override fun getDefaultExtension() = "go"
    override fun getIcon() = GoLspIcons.GO
}
