package com.github.pocikode.go_lsp_intellij

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import java.nio.file.Files
import java.nio.file.Path

internal object GoDependencyActions {
    fun selectedModuleFile(project: Project): VirtualFile? =
        FileEditorManager.getInstance(project).selectedFiles.firstOrNull { GoLspSupport.isGoModuleLanguageFile(it) }

    fun hasModuleContext(project: Project, selectedFile: VirtualFile? = null): Boolean {
        if (selectedFile != null && GoLspSupport.isGoModuleLanguageFile(selectedFile)) return true
        val base = project.basePath?.let(Path::of) ?: return false
        if (Files.exists(base.resolve("go.mod")) || Files.exists(base.resolve("go.work"))) return true
        return runCatching {
            Files.walk(base, 6).use { paths -> paths.anyMatch { it.fileName.toString() == "go.mod" } }
        }.getOrDefault(false)
    }

    fun refresh(project: Project, activate: Boolean = true) {
        object : Task.Backgroundable(project, "Refreshing Go dependencies", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                GoDependencyService.getInstance(project).refresh(indicator)
            }

            override fun onFinished() {
                if (activate) ToolWindowManager.getInstance(project).getToolWindow(GoDependenciesToolWindowFactory.ID)?.show()
            }
        }.queue()
    }
}

abstract class GoModuleCommandAction(
    text: String,
    description: String,
    private val arguments: List<String>,
    private val workspaceArguments: List<String>? = null,
) : AnAction(text, description, null) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val manifest = event.getData(CommonDataKeys.VIRTUAL_FILE)
            ?.takeIf(GoLspSupport::isGoModuleLanguageFile)
            ?: GoDependencyActions.selectedModuleFile(project)
        FileDocumentManager.getInstance().saveAllDocuments()
        object : Task.Backgroundable(project, templatePresentation.text, true) {
            private var results: List<GoToolResult> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                results = GoDependencyService.getInstance(project)
                    .runModuleCommand(arguments, workspaceArguments, manifest, indicator)
            }

            override fun onFinished() {
                val success = results.isNotEmpty() && results.all { !it.timedOut && it.exitCode == 0 }
                val detail = when {
                    results.isEmpty() -> "The Go executable or module directory was not found."
                    results.any(GoToolResult::timedOut) -> "The command timed out."
                    results.any { it.stderr.isNotBlank() } -> results.map(GoToolResult::stderr).filter(String::isNotBlank).joinToString("\n").trim()
                    results.any { it.stdout.isNotBlank() } -> results.map(GoToolResult::stdout).filter(String::isNotBlank).joinToString("\n").trim()
                    else -> if (success) "Dependency files are up to date." else "The command failed."
                }
                NotificationGroupManager.getInstance().getNotificationGroup("Go LSP")
                    .createNotification(templatePresentation.text, detail, if (success) NotificationType.INFORMATION else NotificationType.ERROR)
                    .notify(project)
                ToolWindowManager.getInstance(project).getToolWindow(GoDependenciesToolWindowFactory.ID)?.show()
            }
        }.queue()
    }

    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isEnabledAndVisible = project != null &&
            !GoLspSupport.isNativeGoPluginLoaded() &&
            GoDependencyActions.hasModuleContext(project, event.getData(CommonDataKeys.VIRTUAL_FILE))
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

}

class GoModTidyAction : GoModuleCommandAction("Go Mod Tidy", "Add missing and remove unused module requirements", listOf("mod", "tidy"))
class GoModDownloadAction : GoModuleCommandAction("Go Mod Download", "Download modules to the local cache", listOf("mod", "download"))
class GoModVendorAction : GoModuleCommandAction(
    "Go Mod Vendor",
    "Create or update the vendor directory",
    listOf("mod", "vendor"),
    listOf("work", "vendor"),
)
class GoGetUpdateAction : GoModuleCommandAction("Update Go Dependencies", "Update direct and indirect module dependencies", listOf("get", "-u", "./..."))

class RefreshGoDependenciesAction : AnAction(
    "Refresh Go Dependencies",
    "Reload module versions, updates, problems, vulnerabilities, and the dependency graph",
    AllIcons.Actions.Refresh,
) {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let(GoDependencyActions::refresh)
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project?.let(GoDependencyActions::hasModuleContext) == true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
