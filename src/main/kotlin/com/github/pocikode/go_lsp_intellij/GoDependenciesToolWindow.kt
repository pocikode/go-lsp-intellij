package com.github.pocikode.go_lsp_intellij

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class GoDependenciesToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = GoDependenciesPanel(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(
            com.intellij.ui.content.ContentFactory.getInstance().createContent(panel, "", false),
        )
    }

    override fun shouldBeAvailable(project: Project): Boolean = !GoLspSupport.isNativeGoPluginLoaded()

    companion object {
        const val ID = "Go Dependencies"
    }
}

private class GoDependenciesPanel(project: Project, parentDisposable: Disposable) : SimpleToolWindowPanel(true, true) {
    private val dependencyModel = DependencyTableModel()
    private val vulnerabilityModel = VulnerabilityTableModel()
    private val dependencyTable = JBTable(dependencyModel)
    private val vulnerabilityTable = JBTable(vulnerabilityModel)
    private val graphTree = Tree(DefaultMutableTreeNode("No dependency graph loaded"))
    private val details = JTextArea()
    private val errors = JBLabel()

    init {
        val refresh = ActionManager.getInstance().getAction("GoLsp.RefreshDependencies")
            ?: RefreshGoDependenciesAction()
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("GoDependencies", DefaultActionGroup(refresh), true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        dependencyTable.autoCreateRowSorter = true
        dependencyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        dependencyTable.selectionModel.addListSelectionListener {
            val row = dependencyTable.selectedRow
            details.text = if (row >= 0) moduleDetails(dependencyModel.item(dependencyTable.convertRowIndexToModel(row))) else ""
        }
        vulnerabilityTable.autoCreateRowSorter = true
        details.isEditable = false
        details.lineWrap = true
        details.wrapStyleWord = true
        details.border = JBUI.Borders.empty(8)

        val dependencySplit = OnePixelSplitter(true, 0.7f).apply {
            firstComponent = JBScrollPane(dependencyTable)
            secondComponent = JBScrollPane(details)
        }
        val tabs = JBTabbedPane().apply {
            addTab("Modules", dependencySplit)
            addTab("Dependency Graph", JBScrollPane(graphTree))
            addTab("Vulnerabilities", JBScrollPane(vulnerabilityTable))
        }
        val body = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(0, 0, 4, 0)
            add(errors, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }
        setContent(body)

        project.messageBus.connect(parentDisposable).subscribe(
            GoDependencyReportListener.TOPIC,
            GoDependencyReportListener(::render),
        )
        render(GoDependencyService.getInstance(project).report)
    }

    private fun render(report: GoDependencyReport) {
        dependencyModel.items = report.modules.filterNot(GoModuleDependency::main)
        vulnerabilityModel.items = report.vulnerabilities
        graphTree.model = DefaultTreeModel(graphRoot(report))
        TreeUtil.expand(graphTree, 2)
        errors.text = report.errors.joinToString("  ")
        errors.isVisible = report.errors.isNotEmpty()
        errors.border = if (errors.isVisible) JBUI.Borders.empty(6, 8) else JBUI.Borders.empty()
    }

    private fun graphRoot(report: GoDependencyReport): DefaultMutableTreeNode {
        val main = report.modules.filter(GoModuleDependency::main).map { GoModuleVersion(it.path, it.version) }
        val root = DefaultMutableTreeNode(if (main.size == 1) main.single().displayName else "Workspace modules")
        if (report.graph.isEmpty()) return root

        val outgoing = report.graph.groupBy(GoModuleGraphEdge::from)
        val roots = main.ifEmpty { listOf(report.graph.first().from) }
        val visited = linkedSetOf<GoModuleVersion>()
        roots.forEach { rootVersion ->
            val parent = if (roots.size == 1) root else DefaultMutableTreeNode(rootVersion.displayName).also(root::add)
            visited += rootVersion
            addGraphChildren(parent, rootVersion, outgoing, visited, linkedSetOf(rootVersion), 0)
        }
        return root
    }

    private fun addGraphChildren(
        parent: DefaultMutableTreeNode,
        module: GoModuleVersion,
        outgoing: Map<GoModuleVersion, List<GoModuleGraphEdge>>,
        visited: MutableSet<GoModuleVersion>,
        path: Set<GoModuleVersion>,
        depth: Int,
    ) {
        if (depth >= MAX_GRAPH_DEPTH) return
        outgoing[module].orEmpty().sortedBy { it.to.path }.forEach { edge ->
            val cycle = edge.to in path
            val repeated = !cycle && !visited.add(edge.to)
            val suffix = when {
                cycle -> " (cycle)"
                repeated -> " (shown above)"
                else -> ""
            }
            val child = DefaultMutableTreeNode(edge.to.displayName + suffix)
            parent.add(child)
            if (!cycle && !repeated) addGraphChildren(child, edge.to, outgoing, visited, path + edge.to, depth + 1)
        }
    }

    private fun moduleDetails(module: GoModuleDependency): String = buildString {
        append(module.path).append(' ').append(module.version.ifEmpty { "(local)" }).append('\n')
        module.replacement?.let { append("Replaced by: ").append(it.displayName).append('\n') }
        module.update?.let { append("Update: ").append(it.displayName).append('\n') }
        module.deprecated?.let { append("Deprecated: ").append(it).append('\n') }
        if (module.retracted.isNotEmpty()) append("Retracted: ").append(module.retracted.joinToString("; ")).append('\n')
    }.trim()

    private val GoModuleVersion.displayName: String get() = if (version.isEmpty()) path else "$path@$version"

    private companion object {
        const val MAX_GRAPH_DEPTH = 12
    }
}

private class DependencyTableModel : AbstractTableModel() {
    var items: List<GoModuleDependency> = emptyList()
        set(value) {
            field = value
            fireTableDataChanged()
        }

    fun item(row: Int): GoModuleDependency = items[row]
    override fun getRowCount(): Int = items.size
    override fun getColumnCount(): Int = 4
    override fun getColumnName(column: Int): String = arrayOf("Module", "Version", "Available", "Status")[column]
    override fun getValueAt(row: Int, column: Int): Any = when (column) {
        0 -> items[row].path
        1 -> items[row].effectiveVersion
        2 -> items[row].update?.version.orEmpty()
        else -> items[row].status
    }
}

private class VulnerabilityTableModel : AbstractTableModel() {
    var items: List<GoVulnerability> = emptyList()
        set(value) {
            field = value
            fireTableDataChanged()
        }

    override fun getRowCount(): Int = items.size
    override fun getColumnCount(): Int = 6
    override fun getColumnName(column: Int): String = arrayOf("ID", "Module", "Version", "Fixed In", "Called", "Summary")[column]
    override fun getColumnClass(column: Int): Class<*> = if (column == 4) Boolean::class.javaObjectType else String::class.java
    override fun getValueAt(row: Int, column: Int): Any = when (column) {
        0 -> items[row].id
        1 -> items[row].modulePath
        2 -> items[row].foundVersion
        3 -> items[row].fixedVersion.orEmpty()
        4 -> items[row].called
        else -> items[row].summary
    }
}
