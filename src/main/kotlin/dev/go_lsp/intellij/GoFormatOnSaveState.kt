package dev.go_lsp.intellij

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.PROJECT)
@State(name = "GoFormatOnSave", storages = [Storage("go-format-on-save.xml")])
class GoFormatOnSaveState : PersistentStateComponent<GoFormatOnSaveState> {
    var enabled: Boolean = false

    override fun getState(): GoFormatOnSaveState = this

    override fun loadState(state: GoFormatOnSaveState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(project: Project): GoFormatOnSaveState = project.getService(GoFormatOnSaveState::class.java)
    }
}
