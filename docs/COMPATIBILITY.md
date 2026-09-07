# Compatibility

## Initial Baseline

- IntelliJ Platform: 2024.2
- Build number: `242`
- Product target: IntelliJ IDEA Community
- Build JDK: 21
- Plugin bytecode: Java 17
- LSP client: LSP4IJ `0.21.1-20260905-011817`
- Language server: installed `gopls`

The LSP4IJ version is pinned because nightly versions can change independently of this plugin. Upgrade it deliberately and run the verifier and integration tests before release.

## IntelliJ Editions

LSP4IJ is used instead of the official IntelliJ LSP API because the official API is not available to IntelliJ IDEA Community. The plugin should remain free of `com.intellij.modules.ultimate` and `com.intellij.modules.lsp` dependencies while Community support is a goal. Go files are associated by filename mapping so the bundled TextMate Go grammar remains active.

## GoLand

GoLand already ships native Go support. Registering another Go language implementation can produce duplicate file types, completion providers, inspections, and actions. GoLand is not an initial supported product. A future GoLand mode must detect the native Go plugin and define an explicit coexistence strategy.

## Go Versions

The plugin does not impose a Go version. `gopls` determines language semantics and uses the workspace/toolchain configuration. The current release uses whichever installed `gopls` the user selects or the discovery logic finds.

Future managed installation must support selecting a Go version without changing the current explicit-path behavior.

## Known Risks

- LSP4IJ feature adapters vary by version.
- `gopls` capabilities vary by version.
- Large workspaces can take time to load and analyze.
- A missing or non-executable `gopls` path prevents server startup.
- Multiple Go installations can make automatic discovery ambiguous.
- When the native Go plugin is installed, the Go LSP filename mapping is skipped so native and LSP navigation providers cannot return duplicate targets. GoLand coexistence remains unsupported beyond this guard.
