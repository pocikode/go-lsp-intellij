package icons

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object GoLspIcons {
    @JvmField
    val GO: Icon = IconLoader.getIcon("/icons/go.svg", GoLspIcons::class.java)

    /** Shown for `go.mod`, `go.sum` and the workspace files, which GoLand also icons separately. */
    @JvmField
    val GO_MODULE: Icon = IconLoader.getIcon("/icons/gomod.svg", GoLspIcons::class.java)
}
