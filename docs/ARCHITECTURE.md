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
- `GoStructFields`, `GoStructTagEdits`, and `GoAddKeyToTagsIntention`: find the struct enclosing the caret and add a chosen tag key to its eligible fields. This action is local because `gopls` has no tag-generation code action, and text-backed because TextMate provides no field PSI. The pure text edit planner carries the tests.
- `GoFillAllFieldsIntention`: explicitly requests only `refactor.rewrite.fillStruct` over the caret's
  line with an invoked trigger, then delegates lazy action resolution and workspace-edit application
  to `LspIntentionAction`. The generic platform request is automatic and zero-width, which does not
  reliably surface this `gopls` rewrite for TextMate Go files.
- `GoLspColors`: the Go colour keys, under GoLand's external names, shared by the mapping and the extensions above without pulling in the LSP module.
- `GoLspTestSourcesFilter`: reports `*_test.go` as test sources, putting them in the platform's built-in "Tests" scope so File Colors tints them green, as in GoLand.
- `GoTodoComments` and `GoTodoPatternBuilder`: scan Go comments and expose their ranges through the
  platform's `IndexPatternBuilder`. TextMate already indexes TODO words as candidate matches but its
  PSI lexer is empty, so exact TODO items, the Current File view, and navigation need this Go-only
  lexer. The platform still owns configured patterns, filtering, highlighting, and the tool window.
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
- `GoMainFunction`: finds a runnable `func main()` in a `package main` file. It uses text rather than PSI so the runner has no LSP dependency; its matching tests stay pure JUnit.
- `GoMainLineMarkerProvider`: places the run arrow on the `main` identifier by offset inside the TextMate PSI leaf.
- `GoRunConfiguration`, `GoRunConfigurationType` and `GoRunSettingsEditor`: the editable "Go Run" configuration. It stores the working directory, package or file target, Go tool arguments, program arguments, and environment.
- `GoRunRunningState`: starts `go run`, putting Go tool arguments before the target and program arguments after it, then uses the platform's standard Run console.
- `GoRunRunner`: creates or reuses the `go run .` configuration behind a main-function gutter click.
- `GoTestFunctions`: finds test declarations and statically named direct or table-driven subtests in a Go file's text, and builds the `-run` patterns that select them. Pure text handling, and where the tests are.
- `GoTestEventTranslator`: turns the `go test -json` event stream into the service messages the platform's SM test runner builds its tree from. Nodes are id-based and a test's start is deferred; see below. Non-event process output passes through untouched; event output, including `fmt`, `log`, and panic stacks, is attached to its test. A package failure marks any still-open test failed so an abrupt panic cannot become ignored.
- `GoTestRunConfiguration`, `GoTestRunConfigurationType` and `GoTestSettingsEditor`: the "Go Test" run configuration. It stores the `go test` command line - directory, package pattern, `-run` pattern, extra flags, environment - rather than a friendlier abstraction over it, so what the gutter generated stays readable and editable.
- `GoTestRunningState`: builds and starts the `go test -json -v` process and attaches the SM console to it; verbose mode is explicit so successful-test logs are always requested.
- `GoTestConsoleProperties`: wires the converter, the locator and the rerun-failed action to the platform runner, and turns on the id-based tree. `GoTestEventsConverter` and `GoTestRerunFailedAction` live beside it.
- `GoTestLocator`: resolves a node in the tree back to a line in a Go file and maps between source directories and package import paths through the module path in `go.mod`.
- `GoTestLineMarkerProvider`: the gutter actions, placed by offset rather than by PSI element; it reads IntelliJ's persisted test state through `RunLineMarkerContributor` so the action reflects the last outcome. See below.
- `GoTestRunner`: creates or reuses a run configuration for a test, a file or a package and starts it, which is what a `RunConfigurationProducer` would normally do.
- `GoTestOutputFilter` and `GoTestConsoleFilterProvider`: turn the `foo_test.go:12` in front of a failure into a link to that line.

## Descriptor Layout

`plugin.xml` registers only platform-independent parts: settings, the configurable, the notification group, the file icon provider, the Go colour scheme additions, TODO comment ranges, the tag-generation intention, format-on-save, and both local Go runners. `go-lsp.xml` registers the server support provider, the navigation and highlighting extensions, the three code vision providers, and the restart action, and is loaded through `<depends optional="true" config-file="go-lsp.xml">com.intellij.modules.lsp</depends>`. Builds without the LSP module load the plugin without the server integration; everything the code vision needs comes from `gopls`, so it belongs there too.

The standard platform LSP adapter exposes most `gopls` code actions in the intention menu. Prefer
that route unless a concrete action is missing. **Fill all fields** is the exception above because
the request range and trigger affect whether `gopls` returns it; the plugin owns only that request,
not the rewrite or edit application.

The runners stay on the other side of that line. Nothing in them asks `gopls` anything: main and test declarations come from file text, programs use `go run`, and the test tree comes from `go test -json`. They therefore work in a build with no LSP module. Keep them that way; reaching for `documentSymbol` to find either declaration would lose that property.

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

## Why The Runners Place Their Own Gutter Markers

The platform's route from a click in the gutter to a run is a `RunLineMarkerContributor`, which is
offered PSI elements and returns an `Info` for the ones it recognises, paired with a
`RunConfigurationProducer` that turns a PSI location into a configuration. Neither has anything to
work with here, for the same reason the code vision is plugin-owned: the bundled TextMate grammar
parses a whole `.go` file into a single PSI leaf, so a contributor would only ever be asked about
"the whole file" and could place at most one arrow, at line 1.

`GoMainLineMarkerProvider` and `GoTestLineMarkerProvider` therefore build `LineMarkerInfo`s with
explicit ranges inside that leaf, from declarations read out of the text. `GoRunRunner` and
`GoTestRunner` do what producers would have done. If a future TextMate release gives `.go` files a
real PSI, all four become replaceable by the platform's own machinery; check that before extending
them.

The test scan also recognises statically named `t.Run` calls and conventional keyed or positional anonymous
struct table pattern. It intentionally does not evaluate Go expressions: cases computed by helper
functions still appear when `go test -json` reports them, but cannot have a source gutter action.
Marker state needs no plugin cache. The SM runner persists each outcome by the location URL emitted
by `GoTestEventTranslator`, and the marker asks `RunLineMarkerContributor.getTestStateIcon` for that
same URL.

The main action runs `go run .` from the source file's directory. A Go command is a package and may
span several files, so a file-only target can compile a different program or fail when another file
defines something `main` uses.

## Why A Test Appears In The Tree When It Finishes

The SM test runner creates a node as either a test or a suite, decided by the message that starts
it. In Go that is not knowable in advance: `TestFoo` is a suite if and only if some `TestFoo/case`
runs, and Go reports `run TestFoo` before it can know either. `GoTestEventTranslator` therefore
holds a test's start until its outcome arrives, by which point every subtest that will ever run has
already claimed it as a parent.

The alternative - starting every test eagerly and correcting later - has no correction to make: a
node that has been announced as a test cannot become a suite, and finishing it to reopen it as one
would show a phantom pass. Deferring costs liveness for a slow test and is the smaller price.

Node ids are used for the same family of reasons. Go interleaves the events of parallel tests, so
the ordering a stack-shaped protocol depends on is not there; `isIdBasedTestTree` and explicit
`parentNodeId` attributes make ordering irrelevant.

## Why There Is No Native Go PSI

The historical Go plugin supplied a complete parser, PSI tree, stub indexes, type model, inspections, run configurations, and debugger. That implementation targets IntelliJ 2016.3 and pre-modern Go. Recreating it would duplicate `gopls` and would delay a usable plugin substantially.

Native PSI can be added later for IntelliJ-only features that LSP cannot provide, but it is not part of the initial architecture.

## Future Downloaded Server

Server installation should be added behind the existing discovery boundary. The intended order is:

1. Explicit configured server.
2. Managed downloaded server for the selected Go version.
3. `PATH` and Go SDK discovery.

The download implementation must be platform-aware, checksum-verified, cancellable, and isolated per version. It must never replace an explicitly configured executable.
