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
- `GoLspServerDescriptor`: a `ProjectWideLspServerDescriptor` that maps `.go` files to the server, reports the LSP language id `go`, builds the `gopls` command line from settings, answers both the initialization options and `workspace/configuration` with the `gopls` settings map that enables semantic tokens, turns off the platform client's document links so import paths are not underlined as web links, and declares the `documentSymbol` and `workspace/symbol` client capabilities the platform omits. `gopls` tailors both answers to what the client claims: without `hierarchicalDocumentSymbolSupport` it returns a flat symbol list with no signatures and no members, which is what the code vision is built from.
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
- `GoLspSymbolRequests`: the suspending counterparts - `documentSymbol`, `references`, `definition`, and `workspace/symbol` - used by the code vision, which cannot block a highlighting pass on a server round trip.
- `GoLspCodeVisionService`: the project-scoped cache behind every code vision entry. A debounced background refresh asks `gopls` for the file's declarations, then for a reference count per declaration and the `git blame` behind each one; the providers only ever read the result. Counts and authors are keyed by declaration name, so an edit elsewhere in the file leaves them attached to the right declaration. Publishing drops the code vision pass's PSI stamp through `ModificationStampUtil` before restarting the daemon, because a server answer arriving is not a PSI change and the pass would otherwise skip the file.
- `GoLspCodeVisionProviders`: the three `DaemonBoundCodeVisionProvider`s - usages, code author, and "Implement interface" - sharing a base that reads the service and turns a declaration into an entry.
- `GoLspCodeAuthors`: reads `git blame` through the VCS `AnnotationProvider` and formats the committer the way the platform's own code-author vision does. That vision cannot be reused here; see below.
- `GoLspImplementInterfaceService`: the interface chooser and the method generation behind the "Implement interface" entry. It searches interfaces with `workspace/symbol`, reads the chosen one's method signatures from `documentSymbol`, follows embedded interfaces into their own files, writes the missing methods onto the type, and runs the result through `GoLspFormatting` so `goimports` adds what the new signatures reference.
- `RestartGoLspAction`: restarts the project server through `LspServerManager`.
- `GoLspFormatting`: runs Go source text through the local toolchain - `gofmt`, then `goimports` when it is enabled and installed. Shared by format-on-save and by "Implement interface"; every entry point blocks on an external process and must be called off the UI thread.
- `GoFormatOnSave`: registers the project-level Actions on Save integration and sends the current in-memory `.go` document through `GoLspFormatting`.
- `GoFormatOnSaveState`: persists the format-on-save choice per project.

## Descriptor Layout

`plugin.xml` registers only platform-independent parts: settings, the configurable, the notification group, the file icon provider, the Go colour scheme additions, and format-on-save. `go-lsp.xml` registers the server support provider, the navigation and highlighting extensions, the three code vision providers, and the restart action, and is loaded through `<depends optional="true" config-file="go-lsp.xml">com.intellij.modules.lsp</depends>`. Builds without the LSP module load the plugin without the server integration; everything the code vision needs comes from `gopls`, so it belongs there too.

## Process Lifecycle

The platform calls `fileOpened` for each opened file. The provider starts the server the first time a Go file is opened in a project and reuses it afterwards. Go files keep the IntelliJ TextMate Go grammar for lexical highlighting; the LSP client paints semantic tokens over it and supplies the semantic features. The descriptor builds a command equivalent to:

```text
gopls serve
```

The working directory is the IntelliJ project base path. The platform handles JSON-RPC transport, document synchronization, server capabilities, diagnostics, and standard feature adapters. Stop and restart actions are available from the status bar widget and from `Tools | Restart Go Language Server`.

When `Reformat Go files with gofmt` is enabled under `Settings | Tools | Actions on Save`, the save action sends the current in-memory document through `gofmt` and applies its stdout before the platform saves the document. If `Organize imports with goimports` is enabled in `Settings | Tools | Go LSP`, the formatted text is written to a temporary file beside the source file and passed to `goimports`. Running from the source directory lets `goimports` resolve the module, remove unused imports, and group standard-library and third-party imports before the result is copied back. Formatting does not depend on the LSP server being started.

## Why The Code Vision Is Plugin-Owned

The platform already implements a usage count and a code author vision, and both find declarations
by walking the PSI: the usage provider over language-specific elements, the author provider by
asking a `VcsCodeVisionLanguageContext` registered per language which elements are declarations.

Neither can work for Go here. The bundled TextMate grammar that claims `.go` files parses a whole
file into a **single** PSI leaf - its highlighting lexer produces token-sized pieces, its parser does
not - so there is no per-declaration element to answer with, whatever the context says. The
declarations come from `textDocument/documentSymbol` instead, and the entries are built on top.

The providers still join the platform's settings groups (`references` for usages,
`vcs.code.vision` for the author), so the switches under `Settings | Editor | Inlay Hints | Code
Vision` govern them as a user would expect.

## Why There Is No Native Go PSI

The historical Go plugin supplied a complete parser, PSI tree, stub indexes, type model, inspections, run configurations, and debugger. That implementation targets IntelliJ 2016.3 and pre-modern Go. Recreating it would duplicate `gopls` and would delay a usable plugin substantially.

Native PSI can be added later for IntelliJ-only features that LSP cannot provide, but it is not part of the initial architecture.

## Future Downloaded Server

Server installation should be added behind the existing discovery boundary. The intended order is:

1. Explicit configured server.
2. Managed downloaded server for the selected Go version.
3. `PATH` and Go SDK discovery.

The download implementation must be platform-aware, checksum-verified, cancellable, and isolated per version. It must never replace an explicitly configured executable.
