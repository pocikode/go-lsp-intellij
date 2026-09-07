package com.github.pocikode.go_lsp_intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(name = "GoLspSettings", storages = [Storage("GoLspSettings.xml")])
class GoLspSettingsState : PersistentStateComponent<GoLspSettingsState> {
    var goplsPath: String = ""
    var goplsArguments: String = "serve"
    var traceLevel: String = "off"

    override fun getState(): GoLspSettingsState = this

    override fun loadState(state: GoLspSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        @JvmStatic
        fun getInstance(): GoLspSettingsState =
            ApplicationManager.getApplication().getService(GoLspSettingsState::class.java)
    }
}
