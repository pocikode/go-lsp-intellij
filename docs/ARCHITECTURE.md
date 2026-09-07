# Architecture

## Boundary

The plugin owns IntelliJ integration and process configuration. The IntelliJ LSP API owns JSON-RPC transport, document synchronization, and the editor feature adapters. `gopls` owns Go parsing, type checking, package loading, diagnostics, completion, navigation, and refactoring. The local Go toolchain owns save formatting through `gofmt` and optional import organization through `goimports`.

```text
IntelliJ IDEA
    |
    | IntelliJ LSP API (com.intellij.platform.lsp)
    v
Go LSP plugin
    |
    | stdio / JSON-RPC
    v
gopls
    |
    v
Go toolchain and workspace
```

## Main Components

- `GoLspServerSupportProvider`: the `platform.lsp.serverSupportProvider` extension. When a `.go` file is opened and the native Go plugin is not loaded, it resolves `gopls` and asks the platform to start one project-wide server. It also contributes the status bar widget item and notifies once per project when `gopls` is missing.
- `GoLspServerDescriptor`: a `ProjectWideLspServerDescriptor` that maps `.go` files to the server, reports the LSP language id `go`, builds the `gopls` command line from settings, answers both the initialization options and `workspace/configuration` with the `gopls` settings map that enables semantic tokens, and turns off the platform client's document links so import paths are not underlined as web links.
- `GoLspSemanticTokens`: maps `gopls` semantic tokens onto GoLand's own attribute keys by external name, so a GoLand-tuned scheme is honoured and the colours in `colorSchemes/` supply GoLand's defaults otherwise. Returns null for lexical tokens so the TextMate grammar keeps painting them.
- `GoLspImportPathFilter`: drops the package-coloured highlight `gopls` puts on an import path, which it reports with the same token type as a package qualifier.
- `GoStructTags` and `GoLspStructTagAnnotator`: find the `key:` of each struct tag from the file text and paint it apart from the rest of the raw string, as GoLand does. `GoStructTags` is pure text handling and carries the tests.
- `GoLspColors`: the Go colour keys, under GoLand's external names, shared by the mapping and the extensions above without pulling in the LSP module.
- `GoLspTestSourcesFilter`: reports `*_test.go` as test sources, putting them in the platform's built-in "Tests" scope so File Colors tints them green, as in GoLand.
- `GoLspFileIconProvider`: gives `.go` and the Go module files a Go icon in the project view and editor tabs, standing down when the native Go plugin is loaded.
- `GoLspSupport`: shared Go-file, Go-module-file, and native-plugin checks.
- `GoLspDiscovery`: searches configured and conventional executable locations.
- `GoLspSettingsState`: persists user configuration at application scope.
- `GoLspConfigurable`: exposes executable and command arguments in Settings, and is linked from the status bar widget.
- `GoLspReferenceProvider`, `GoLspDeclarationProvider`, `GoLspSymbol`, and `GoLspUsageSearcher`: Go navigation is handled by the plugin rather than the platform client. The platform's reference provider only answers while a Go to Declaration action runs, so Cmd/Ctrl+hover never styles call sites, and it never reports declarations, so Cmd/Ctrl+click on a declaration goes nowhere. The reference provider resolves the identifier under the caret with `textDocument/definition` to a `GoLspSymbol`, a navigatable symbol that is also a search target. The declaration provider exposes a declaration when the definition points back at the caret. The usage searcher answers searches for a `GoLspSymbol` with `textDocument/references`. Together they give link styling on hover, navigation from references, and the usages popup from declarations, like GoLand. The descriptor disables the platform's go-to-definition support so targets are never duplicated.
- `GoLspRequests`: synchronous `definition`/`references` helpers and offset/position conversion for the components above.
- `RestartGoLspAction`: restarts the project server through `LspServerManager`.
- `GoFormatOnSave`: registers the project-level Actions on Save integration and formats the current in-memory `.go` document with `gofmt`, then optionally `goimports`.
- `GoFormatOnSaveState`: persists the format-on-save choice per project.

## Descriptor Layout

`plugin.xml` registers only platform-independent parts: settings, the configurable, the notification group, the file icon provider, the Go colour scheme additions, and format-on-save. `go-lsp.xml` registers the server support provider and the restart action and is loaded through `<depends optional="true" config-file="go-lsp.xml">com.intellij.modules.lsp</depends>`. Builds without the LSP module load the plugin without the server integration.

## Process Lifecycle

The platform calls `fileOpened` for each opened file. The provider starts the server the first time a Go file is opened in a project and reuses it afterwards. Go files keep the IntelliJ TextMate Go grammar for lexical highlighting; the LSP client paints semantic tokens over it and supplies the semantic features. The descriptor builds a command equivalent to:

```text
gopls serve
```

The working directory is the IntelliJ project base path. The platform handles JSON-RPC transport, document synchronization, server capabilities, diagnostics, and standard feature adapters. Stop and restart actions are available from the status bar widget and from `Tools | Restart Go Language Server`.

When `Reformat Go files with gofmt` is enabled under `Settings | Tools | Actions on Save`, the save action sends the current in-memory document through `gofmt` and applies its stdout before the platform saves the document. If `Organize imports with goimports` is enabled in `Settings | Tools | Go LSP`, the formatted text is written to a temporary file beside the source file and passed to `goimports`. Running from the source directory lets `goimports` resolve the module, remove unused imports, and group standard-library and third-party imports before the result is copied back. Formatting does not depend on the LSP server being started.

## Why There Is No Native Go PSI

The historical Go plugin supplied a complete parser, PSI tree, stub indexes, type model, inspections, run configurations, and debugger. That implementation targets IntelliJ 2016.3 and pre-modern Go. Recreating it would duplicate `gopls` and would delay a usable plugin substantially.

Native PSI can be added later for IntelliJ-only features that LSP cannot provide, but it is not part of the initial architecture.

## Future Downloaded Server

Server installation should be added behind the existing discovery boundary. The intended order is:

1. Explicit configured server.
2. Managed downloaded server for the selected Go version.
3. `PATH` and Go SDK discovery.

The download implementation must be platform-aware, checksum-verified, cancellable, and isolated per version. It must never replace an explicitly configured executable.
