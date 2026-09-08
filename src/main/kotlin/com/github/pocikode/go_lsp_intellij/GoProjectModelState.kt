package com.github.pocikode.go_lsp_intellij

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.PROJECT)
@State(name = "GoProjectModel", storages = [Storage("go-project-model.xml")])
internal class GoProjectModelState : PersistentStateComponent<GoProjectModelState> {
    var sdkPath: String = ""
    var goroot: String = ""
    var buildTags: String = ""
    var goos: String = ""
    var goarch: String = ""
    var cgoEnabled: Boolean = true
    var useVendoring: Boolean = false
    var gopath: String = ""
    var refreshOnManifestChange: Boolean = true

    override fun getState(): GoProjectModelState = this
    override fun loadState(state: GoProjectModelState) = XmlSerializerUtil.copyBean(state, this)

    companion object {
        fun getInstance(project: Project): GoProjectModelState = project.service()
    }
}
