package com.github.pocikode.go_lsp_intellij

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollingUtil
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SymbolKind

/** One method a chosen interface requires, as `Get` plus `(ctx context.Context) (*V1, error)`. */
internal data class GoLspInterfaceMethod(val name: String, val signature: String)

/**
 * The Go text "Implement interface" writes, kept apart from the `gopls` plumbing so it can be read
 * and tested on its own. The body is the one GoLand generates.
 */
internal object GoLspMethodStubs {
    const val BODY_PLACEHOLDER = "panic(\"implement me\")"

    /** `func(ctx context.Context) error` as gopls prints it, reduced to what follows a method name. */
    fun signatureOf(detail: String?): String? =
        detail?.takeIf { it.startsWith("func") }?.removePrefix("func")?.trim()?.takeIf { it.startsWith("(") }

    /** GoLand's default receiver: the first letter of the type, lowercased. */
    fun receiverNameFor(typeName: String): String =
        typeName.firstOrNull()?.takeIf(Char::isLetter)?.lowercaseChar()?.toString() ?: "r"

    fun render(
        typeName: String,
        receiver: String,
        pointer: Boolean,
        methods: List<GoLspInterfaceMethod>,
    ): String = methods.joinToString(separator = "") { method ->
        val star = if (pointer) "*" else ""
        "\n\nfunc ($receiver $star$typeName) ${method.name}${method.signature} {\n" +
            "\t//TODO implement me\n" +
            "\t$BODY_PLACEHOLDER\n" +
            "}"
    }
}

/**
 * The "Implement interface" code vision action: pick an interface, get its methods stubbed out on
 * the type below the entry.
 *
 * `gopls` has no code action for this - Go interfaces are satisfied structurally, so there is
 * nothing for it to offer - which is why the methods are generated here from the signatures
 * `textDocument/documentSymbol` reports for the interface, and the imports they need are left to
 * `goimports` afterwards.
 */
@Service(Service.Level.PROJECT)
class GoLspImplementInterfaceService(private val project: Project, private val scope: CoroutineScope) {
    private var searchJob: Job? = null

    /** Opens the interface chooser for the type declared at [declaration]. */
    fun choose(
        editor: Editor,
        file: VirtualFile,
        declaration: GoLspCodeVisionService.Declaration,
        event: MouseEvent?,
    ) {
        if (GoLspRequests.runningServer(project, file) == null) {
            notify("The Go language server is not running for ${file.name}.")
            return
        }

        GoLspInterfaceChooser(
            onQuery = { query, consumer -> search(file, query, consumer) },
            onChosen = { chosen -> implement(editor, file, declaration, chosen) },
        ).show(editor, event)
    }

    /**
     * Answers the chooser's current query, cancelling the previous one.
     *
     * An empty query means the popup has just opened: `gopls` answers those with nothing, so it is
     * turned into the enclosing module path, which lists the interfaces the project declares itself.
     */
    private fun search(file: VirtualFile, query: String, consumer: (List<GoLspInterface>) -> Unit) {
        searchJob?.cancel()
        searchJob = scope.launch {
            val server = GoLspRequests.runningServer(project, file)
            val effectiveQuery = query.ifBlank { modulePathOf(file).orEmpty() }
            val results = if (server == null) emptyList() else GoLspSymbolRequests.interfaces(server, effectiveQuery)
            withContext(Dispatchers.EDT) { consumer(results) }
        }
    }

    /** The `module` line of the nearest enclosing `go.mod`, which is how gopls names local packages. */
    private suspend fun modulePathOf(file: VirtualFile): String? = readAction {
        var directory = file.parent
        while (directory != null) {
            val goMod = directory.findChild("go.mod")
            if (goMod != null && !goMod.isDirectory) {
                val text = runCatching { VfsUtilCore.loadText(goMod) }.getOrNull() ?: return@readAction null
                return@readAction text.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("module ") }
                    ?.removePrefix("module ")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
            directory = directory.parent
        }
        null
    }

    private fun implement(
        editor: Editor,
        file: VirtualFile,
        declaration: GoLspCodeVisionService.Declaration,
        chosen: GoLspInterface,
    ) {
        scope.launch {
            val server = GoLspRequests.runningServer(project, file) ?: return@launch
            val required = methodsOf(server, chosen)
            if (required.isEmpty()) {
                notify("${chosen.qualifiedName} declares no methods to implement.")
                return@launch
            }

            // A fresh look at the file: the code vision cache may lag the editor by a keystroke, and
            // the insertion offset has to be exact.
            val nodes = GoLspSymbolRequests.documentSymbols(server, file)
            val typeName = declaration.simpleName
            val typeNode = nodes.firstOrNull { it.name == typeName && it.kind == declaration.kind }
                ?: run {
                    notify("Could not locate the declaration of $typeName any more.")
                    return@launch
                }
            val existing = nodes.map { GoLspCodeVisionService.Declaration(it.name, it.kind, it.range, it.selectionRange) }
                .filter { it.methodOf == typeName }

            val missing = required.filterNot { method -> existing.any { it.simpleName == method.name } }
            if (missing.isEmpty()) {
                notify("$typeName already implements ${chosen.qualifiedName}.")
                return@launch
            }

            val document = editor.document
            val plan = readAction {
                // A method always starts a line of its own, so it is always safe to follow. The type
                // is not: one declared inside a `type ( ... )` block ends inside the parentheses,
                // where a func cannot go, and the methods are appended to the file instead.
                val methodEnds = existing.mapNotNull { GoLspRequests.textRange(document, it.range)?.endOffset }
                val typeEnd = GoLspRequests.textRange(document, typeNode.range)
                    ?.takeIf { startsOwnTypeDeclaration(document, it) }
                    ?.endOffset
                val insertAfter = (methodEnds + listOfNotNull(typeEnd)).maxOrNull() ?: document.textLength
                val receiver = existing.firstNotNullOfOrNull { receiverNameOf(document, it) }
                    ?: GoLspMethodStubs.receiverNameFor(typeName)
                val pointer = existing.firstOrNull()?.hasPointerReceiver ?: true
                Insertion(insertAfter, GoLspMethodStubs.render(typeName, receiver, pointer, missing))
            }

            insert(editor, file, plan)
        }
    }

    private class Insertion(val offset: Int, val text: String)

    private suspend fun insert(editor: Editor, file: VirtualFile, plan: Insertion) {
        if (editor.isDisposed) return
        val document = editor.document
        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project, COMMAND_NAME, COMMAND_GROUP, {
                document.insertString(plan.offset, plan.text)
            })
        }

        // goimports adds whatever the new signatures reference - context, the interface's package.
        val inserted = readAction { document.text }
        val formatted = withContext(Dispatchers.IO) { GoLspFormatting.format(project, file, inserted) }

        withContext(Dispatchers.EDT) {
            if (formatted != null && formatted != inserted && document.text == inserted) {
                WriteCommandAction.runWriteCommandAction(project, COMMAND_NAME, COMMAND_GROUP, {
                    document.setText(formatted)
                })
            }
            val caret = document.text.indexOf(GoLspMethodStubs.BODY_PLACEHOLDER, plan.offset)
            if (caret >= 0) {
                editor.caretModel.moveToOffset(caret)
                editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
            }
        }
    }

    /**
     * The methods [chosen] requires, following embedded interfaces into their own files -
     * `io.Closer` in an interface contributes `Close() error` just as a listed method would.
     */
    private suspend fun methodsOf(server: LspServer, chosen: GoLspInterface): List<GoLspInterfaceMethod> {
        val file = server.descriptor.findFileByUri(chosen.location.uri) ?: return emptyList()
        val node = findNode(GoLspSymbolRequests.documentSymbols(server, file), chosen.location.range.start)
            ?: return emptyList()
        val methods = LinkedHashMap<String, GoLspInterfaceMethod>()
        collectMethods(server, file, node, methods, HashSet(), 0)
        return methods.values.toList()
    }

    private suspend fun collectMethods(
        server: LspServer,
        file: VirtualFile,
        node: GoLspSymbolNode,
        methods: MutableMap<String, GoLspInterfaceMethod>,
        visited: MutableSet<String>,
        depth: Int,
    ) {
        if (!visited.add("${file.url}:${node.selectionRange.start.line}:${node.selectionRange.start.character}")) return

        for (child in node.children) {
            when (child.kind) {
                SymbolKind.Method -> {
                    val signature = GoLspMethodStubs.signatureOf(child.detail) ?: continue
                    methods.putIfAbsent(child.name, GoLspInterfaceMethod(child.name, signature))
                }
                // An embedded interface; gopls reports it as a field whose detail is the type name.
                SymbolKind.Field -> {
                    if (depth >= MAX_EMBEDDING_DEPTH) continue
                    val target = GoLspSymbolRequests
                        .definitions(server, file, child.selectionRange.start)
                        .firstOrNull() ?: continue
                    val targetFile = server.descriptor.findFileByUri(target.uri) ?: continue
                    val targetNode = findNode(
                        GoLspSymbolRequests.documentSymbols(server, targetFile),
                        target.range.start,
                    ) ?: continue
                    collectMethods(server, targetFile, targetNode, methods, visited, depth + 1)
                }
                else -> continue
            }
        }
    }

    private fun findNode(nodes: List<GoLspSymbolNode>, position: Position): GoLspSymbolNode? =
        nodes.firstOrNull { it.selectionRange.start == position }
            ?: nodes.firstNotNullOfOrNull { findNode(it.children, position) }

    /** True when the declaration has its own `type` keyword rather than sharing a `type ( ... )` block. */
    private fun startsOwnTypeDeclaration(document: Document, range: TextRange): Boolean {
        val line = document.getLineNumber(range.startOffset)
        val lineRange = TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))
        return document.getText(lineRange).trimStart().startsWith("type ")
    }

    /** The identifier an existing method already uses for its receiver, so new ones match it. */
    private fun receiverNameOf(
        document: Document,
        method: GoLspCodeVisionService.Declaration,
    ): String? {
        val range = GoLspRequests.textRange(document, method.range) ?: return null
        val header = document.getText(range).substringBefore('\n')
        return RECEIVER.find(header)?.groupValues?.get(1)
    }

    private fun notify(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Go LSP")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }

    companion object {
        fun getInstance(project: Project): GoLspImplementInterfaceService = project.service()

        private const val COMMAND_NAME = "Implement Interface"
        private const val COMMAND_GROUP = "go.lsp.implement.interface"
        private const val MAX_EMBEDDING_DEPTH = 8
        private val RECEIVER = Regex("""^func\s*\(\s*([A-Za-z_]\w*)\s""")
    }
}

/**
 * The searchable interface chooser. Every keystroke is answered by `workspace/symbol`, so the list
 * is refilled from `gopls` rather than filtered locally.
 */
private class GoLspInterfaceChooser(
    private val onQuery: (String, (List<GoLspInterface>) -> Unit) -> Unit,
    private val onChosen: (GoLspInterface) -> Unit,
) {
    private val model = CollectionListModel<GoLspInterface>()
    private val list = JBList(model)
    private val searchField = SearchTextField(false)
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD)

    /** Discards answers to a query the user has already typed past. */
    private var latestQuery = 0

    fun show(editor: Editor, event: MouseEvent?) {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.emptyText.text = "Searching…"
        list.cellRenderer = object : ColoredListCellRenderer<GoLspInterface>() {
            override fun customizeCellRenderer(
                list: JList<out GoLspInterface>,
                value: GoLspInterface,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                icon = AllIcons.Nodes.Interface
                append(value.name)
                if (value.packagePath.isNotEmpty()) {
                    append("  ${value.packagePath}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }

        val panel = JPanel(BorderLayout())
        panel.add(searchField, BorderLayout.NORTH)
        panel.add(JBScrollPane(list), BorderLayout.CENTER)
        panel.preferredSize = Dimension(JBUI.scale(520), JBUI.scale(360))

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, searchField)
            .setTitle("Implement Interface")
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .createPopup()
        Disposer.register(popup, alarm)

        ScrollingUtil.installActions(list, searchField.textEditor)
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = scheduleQuery()
        })
        searchField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                if (event.keyCode == KeyEvent.VK_ENTER) choose(popup)
            }
        })
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                choose(popup)
                return true
            }
        }.installOn(list)

        if (event != null) popup.show(RelativePoint(event)) else popup.showInBestPositionFor(editor)
        runQuery()
    }

    private fun choose(popup: JBPopup) {
        val selected = list.selectedValue ?: return
        popup.closeOk(null)
        onChosen(selected)
    }

    private fun scheduleQuery() {
        alarm.cancelAllRequests()
        alarm.addRequest(::runQuery, SEARCH_DELAY_MS)
    }

    private fun runQuery() {
        val query = searchField.text.trim()
        val id = ++latestQuery
        list.emptyText.text = "Searching…"
        onQuery(query) { results ->
            if (id != latestQuery) return@onQuery
            model.replaceAll(results)
            list.emptyText.text = if (query.isEmpty()) "Type to search for an interface" else "No interfaces found"
            if (results.isNotEmpty()) list.selectedIndex = 0
        }
    }

    private companion object {
        const val SEARCH_DELAY_MS = 250
    }
}
