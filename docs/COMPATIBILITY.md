# Compatibility

## Baseline

- IntelliJ Platform: 2025.2 (compiled against IntelliJ IDEA Ultimate 2025.2.6)
- Since-build: `252.25557` (IntelliJ IDEA 2025.2.1, the first build where the LSP API is free to use)
- Product target: IntelliJ IDEA
- Build JDK: 21
- Plugin bytecode: Java 21
- LSP client: IntelliJ LSP API (`com.intellij.modules.lsp`)
- Language server: installed `gopls`

## IntelliJ Editions

The LSP API ships in the IntelliJ IDEA (Ultimate) distribution and in the other commercial JetBrains IDEs. Since 2025.2.1 it works without a paid license, and since 2025.3 there is a single IntelliJ IDEA distribution, so the plugin is compiled against IU.

The IntelliJ IDEA Community 2025.2 build and Android Studio do not contain the module. The dependency on `com.intellij.modules.lsp` is optional, so the plugin still installs there; only the `gofmt` format-on-save action works. JetBrains announced that the LSP client is open-sourced starting with 2026.1.4, which will extend it to those products.

The plugin must not depend on `com.intellij.modules.ultimate`. Go files are associated by extension so the bundled TextMate Go grammar remains active.

## Platform APIs Beyond The LSP Client

The code vision uses three platform APIs worth recording, because they are the ones most likely to
move under the plugin:

- `DaemonBoundCodeVisionProvider` and `ModificationStampUtil`, for the entries themselves and for
  forcing the code vision pass to run again when a `gopls` answer arrives.
- `AnnotationProvider` and `FileAnnotation`, for the committer behind the code author entry. It is
  Git-only, as the platform's own code author vision is.
- `ShortNameType`, which the platform uses to abbreviate a committer's name, lives in a platform
  module the plugin does not compile against. The full name is shown instead, which is that
  setting's default.

The plugin deliberately does **not** implement `VcsCodeVisionLanguageContext`, the extension point
the platform offers for contributing a code author vision to a language. It cannot work for Go here;
see `docs/ARCHITECTURE.md`.

The platform client declares no `documentSymbol` or `workspace/symbol` client capabilities, so
`GoLspServerDescriptor` overrides `clientCapabilities` to add them. If a future platform release
declares them itself, that override should be re-checked rather than removed blindly - `gopls`
changes what it returns based on what is declared.

## API Names

IntelliJ 2026.1.4 renamed the LSP API classes (`LspServerSupportProvider` to `LspIntegrationProvider`, `LspServerDescriptor` to `LspClientDescriptor`, `LspServerManager` to `LspClientManager`). The old names are deprecated but keep working. The plugin uses the 2025.2 names until the since-build is raised.

## GoLand

GoLand already ships native Go support. Registering another Go language implementation can produce duplicate file types, completion providers, inspections, and actions. When the native Go plugin (`org.jetbrains.plugins.go`) is loaded, the server support provider does not start `gopls`. The check uses `PluginManagerCore.isLoaded`, not `isPluginInstalled`, because the Go plugin can be installed yet fail to load when the Ultimate module is disabled without a subscription. GoLand is not an initial supported product beyond that guard.

## Go Versions

The plugin does not impose a Go version. `gopls` determines language semantics and uses the workspace/toolchain configuration. The current release uses whichever installed `gopls` the user selects or the discovery logic finds.

Future managed installation must support selecting a Go version without changing the current explicit-path behavior.

## Known Risks

- The set of LSP features supported by the platform grows with each IDE release; see `docs/FEATURES.md`.
- `gopls` capabilities vary by version.
- Large workspaces can take time to load and analyze.
- A missing or non-executable `gopls` path prevents server startup; the plugin shows a notification with a link to the settings.
- Multiple Go installations can make automatic discovery ambiguous.
- The code vision depends on how the bundled TextMate grammar parses `.go` files - today, into a
  single PSI leaf per file. A future TextMate release that produces real per-token leaves would not
  break anything, but it would make the platform's own code author vision reusable and this
  plugin's worth revisiting.
- Reference counts cost one `textDocument/references` call per declaration. They are cached and
  computed in the background, capped at 200 declarations per file and four concurrent requests, but
  a very large workspace still makes them slow to appear.
