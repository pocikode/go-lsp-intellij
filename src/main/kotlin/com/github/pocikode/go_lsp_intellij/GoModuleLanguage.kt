package com.github.pocikode.go_lsp_intellij

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import icons.GoLspIcons

object GoModuleLanguage : Language("GoModule")

object GoModuleFileType : LanguageFileType(GoModuleLanguage) {
    override fun getName(): String = "Go Module Files"
    override fun getDescription(): String = "Go module, workspace, and checksum file"
    override fun getDefaultExtension(): String = "mod"
    override fun getIcon() = GoLspIcons.GO_MODULE
}

/** Claims exact Go manifest names only when JetBrains' native Go language is not loaded. */
class GoModuleFileTypeOverrider : FileTypeOverrider {
    override fun getOverriddenFileType(file: VirtualFile) =
        GoModuleFileType.takeIf { !GoLspSupport.isNativeGoPluginLoaded() && GoLspSupport.isGoModuleFile(file) }
}

class GoModuleParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = GoModuleLexer()

    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val marker = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        marker.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType(): IFileElementType = FILE
    override fun getWhitespaceTokens(): TokenSet = TokenSet.create(TokenType.WHITE_SPACE)
    override fun getCommentTokens(): TokenSet = TokenSet.create(GoModuleTokenTypes.COMMENT)
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY
    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = GoModulePsiFile(viewProvider)

    private companion object {
        val FILE = IFileElementType(GoModuleLanguage)
    }
}

private class GoModulePsiFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, GoModuleLanguage) {
    override fun getFileType() = GoModuleFileType
    override fun toString(): String = "Go module file"
}
