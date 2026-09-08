# Agent Instructions

## Current Status

- This is an IntelliJ IDEA plugin scaffold, not a GoLand replacement yet.
- The implemented foundation is Go filename mapping with TextMate syntax highlighting plus GoLand-matched `gopls` semantic token colouring, `go.mod`/`go.work` LSP support, Go module maintenance actions and a dependency report, Go file icons, green test files in the project view, navigable Go comments in the TODO tool window, installed `gopls` discovery with a missing-server notification, startup through the IntelliJ LSP API over stdio, persistent `gopls` settings, a status bar widget, a restart action, and native Go plugin conflict suppression.
- GoLand's code vision above a Go declaration is implemented: a usage count, the last committer, and "Implement interface". All three are built from `gopls` rather than the PSI, and all three are plugin-owned rather than the platform's - see the Architecture section for why that is not a choice.
- Editor features (diagnostics, completion, hover, navigation, references, code actions, formatting, folding, inlay hints) come from the platform LSP client and `gopls`; which ones exist depends on the IDE version. Debugging, coverage, automatic `gopls` download, and Go-version selection are roadmap work. Format-on-save is enabled by default through local `gofmt`, with optional `goimports` import organization and a per-project opt-out under Actions on Save.
- Go runners are implemented without LSP: a gutter run arrow on `func main()` backed by a "Go Run" configuration and `go run .`, plus gutter arrows on `*_test.go`, a "Go Test" configuration, and the platform test tree fed from `go test -json`, with subtests nested and rerun-failed. See the Architecture section for why the no-LSP boundary is deliberate.
- Read `docs/FEATURES.md` and `docs/ROADMAP.md` before describing or extending feature coverage. See the Documentation section for what to update once it has changed.

## Build

- The build targets the locally installed IntelliJ IDEA `2026.2.2` / since-build `262.10315`, uses the IntelliJ LSP API, compiles plugin bytecode for Java 25, and uses the IDE's bundled Java 25 runtime.
- The platform dependency is `/Applications/IntelliJ IDEA.app` by default and can be overridden with `-PplatformPath=/path/to/IntelliJ IDEA.app`. Keep it local: do not replace it with an `intellijIdea(...)` dependency or a release selector that downloads a full IDE into Gradle's cache.
- The repository points Gradle toolchain discovery at the installed IDE's Java 25 runtime. The standard Gradle wrapper downloads the configured Gradle version (`9.7.1`) automatically.
- `gopls` is an external runtime prerequisite. The plugin currently does not download it; use an installed executable or configure its full path in `Settings | Tools | Go LSP`.
- `go` itself is a runtime prerequisite of the test runner, found by `GoLspDiscovery.findGoTool`. There is no setting for it: `PATH` and the conventional install locations are searched, `/usr/local/go/bin` included, because an IDE launched from Finder does not inherit the user's shell `PATH`.
- Useful commands:
  - `./gradlew test` runs the JUnit 5 tests under `src/test/kotlin`.
  - `./gradlew buildPlugin` builds `build/distributions/go-lsp-intellij-<version>.zip`.
  - `./gradlew runIde` launches a development IntelliJ sandbox.
  - `./gradlew verifyPluginStructure` checks the plugin descriptor and archive structure quickly.
  - `./gradlew verifyPlugin` runs Plugin Verifier against the installed IDE and downloads no IDE distribution.
- The normal verification order is `test`, `buildPlugin`, `verifyPluginStructure`, then `verifyPlugin` when platform/API compatibility needs checking.
- `verifyPlugin` currently exits non-zero on a pre-existing internal API usage - `ShowUsagesAction.showUsages`, which the code vision needs and the platform offers no public equivalent for. The verdict line above the failure is the one that matters: read the report for "Compatible" and for new problems naming the classes you touched, rather than treating the exit code as the answer.

## Architecture

- Keep IntelliJ integration in `src/main/kotlin/com/github/pocikode/go_lsp_intellij` and plugin registrations in `src/main/resources/META-INF/plugin.xml`.
- Everything that references `com.intellij.platform.lsp` must be registered in `META-INF/go-lsp.xml`, which is loaded through the optional `com.intellij.modules.lsp` dependency. `plugin.xml` must stay loadable without the LSP module.
- `GoLspServerSupportProvider` decides when to start a server; `GoLspServerDescriptor` owns the `gopls` command line, file mapping, and client capabilities; `GoLspSupport` owns the Go-file and native-plugin checks; `GoLspDiscovery` owns executable lookup; `GoLspSettingsState` owns persisted application settings; `GoLspRequests` owns synchronous `gopls` requests made outside the platform client and `GoLspSymbolRequests` the suspending ones; `GoLspFormatting` owns every `gofmt`/`goimports` invocation; do not duplicate these responsibilities.
- `GoDependencyService` owns every `go list`, `go mod`, `go get`, and `govulncheck` invocation used
  for dependency management. `GoDependencyParsers` owns their output formats; keep parsing out of UI
  components and keep mutating commands explicit rather than running them during refresh.
- `GoModuleLanguage` gives all four exact module/checksum filenames a language-backed PSI file,
  lexical highlighting, and local completion without claiming arbitrary `.mod` or `.sum` files.
  Keep `go.mod`/`go.work` mapped to `gopls`; do not map checksum files, which the server does not
  support. Current `gopls` deliberately returns no completion for `go.mod`, so its local completion
  is not a duplicate; `go.work` completion remains server-owned.
- Go-source navigation is plugin-owned: `GoLspReferenceProvider` (references, hover styling), `GoLspDeclarationProvider` (declarations), `GoLspSymbol` (navigatable symbol + search target), `GoLspUsageSearcher` (usages). `GoLspServerDescriptor` disables the platform's go-to-definition support so the built-in definition provider cannot duplicate targets; do not re-enable it without removing `GoLspReferenceProvider`. Document links remain enabled because `gopls` uses them for module-file navigation, while `GoLspImportPathFilter` removes their hyperlink presentation from Go source. Keep other editor features on the platform client where it offers them.
- The code vision is plugin-owned out of necessity, not preference. The bundled TextMate grammar parses a whole `.go` file into a **single** PSI leaf - its highlighting lexer produces token-sized pieces, its parser does not - so nothing that finds declarations by walking the PSI can work for Go here. That rules out the platform's own usage-count provider and its `VcsCodeVisionLanguageContext` extension point for the code author; do not reach for either again without first checking whether the TextMate PSI has changed. Declarations come from `textDocument/documentSymbol`.
- `GoLspServerDescriptor` must keep declaring the `documentSymbol` (`hierarchicalDocumentSymbolSupport`) and `workspace/symbol` client capabilities. The platform client declares neither, and `gopls` downgrades its answer to a flat list with no signatures and no members when they are missing, which silently empties the code vision and "Implement interface".
- `GoLspCodeVisionService` owns all code vision state. Providers must only read its cache: a `textDocument/references` call per declaration cannot be made from a highlighting pass. When publishing, it must keep dropping the pass's PSI stamp through `ModificationStampUtil` before restarting the daemon - a server answer is not a PSI change, and without that the entries wait for the next keystroke. Counts and authors are keyed by declaration name, not position, deliberately.
- `GoLspCodeAuthors` reads `git blame` from the file on disk, so authors are only recomputed for a saved document. Do not blame an unsaved document; the line numbers no longer line up.
- Go TODO support belongs to `GoTodoComments` and `GoTodoPatternBuilder`. TextMate's TODO index finds
  candidate files but its empty PSI lexer cannot recover exact items; keep supplying Go comment
  ranges through `IndexPatternBuilder` rather than replacing the shared `textmate` indexer or
  building a custom TODO tool window.
- Highlighting is split: the bundled TextMate grammar owns lexical tokens, and `GoLspSemanticTokens` owns the semantic ones. Return null from the mapping rather than guessing, so TextMate keeps the token. `GoLspServerDescriptor` must keep `semanticTokens` enabled in the `gopls` settings map; `gopls` defaults it to off.
- Two Go colouring rules cannot be expressed as a token mapping and live beside it: `GoLspImportPathFilter` removes the package colour from import paths (gopls reports them as `namespace`, like a qualifier), and `GoLspStructTagAnnotator` splits struct tag keys out of the raw string. Both work by position, which `getTextAttributesKey` does not get.
- Project view parity goes through platform hooks rather than custom rendering: `GoLspFileIconProvider` for icons and `GoLspTestSourcesFilter` for the green test files, the latter by joining the built-in "Tests" scope rather than declaring a colour. Both stand down when the native Go plugin is loaded.
- The keys live in `GoLspColors`, which must stay free of `com.intellij.platform.lsp` so extensions outside `go-lsp.xml` can use it. The mapping must name GoLand's `GO_*` attribute keys, not `DefaultLanguageHighlighterColors` constants: `GO_PACKAGE` and `GO_TYPE_REFERENCE` have explicit colours in GoLand's scheme files that no fallback key reproduces. `colorSchemes/GoLsp*.xml` supply those values for schemes that do not set them; a scheme that names a key wins over them. This is the one place the plugin deliberately shares names with the native Go plugin, so a GoLand-tuned theme applies here too. Read `docs/FEATURES.md` before changing a key choice.
- The test runner must keep working without `com.intellij.platform.lsp`. It is registered in `plugin.xml`, not `go-lsp.xml`, and everything it needs comes from `go test -json` and the file's own text; reaching for `documentSymbol` to find test functions would be the easy way to lose that. `GoTestFunctions` owns finding test declarations and statically named table cases and building `-run` patterns; `GoTestEventTranslator` owns the JSON-to-service-message translation and the stable location URLs used by tree navigation and persisted gutter state, and takes its output as a lambda so it is unit-testable without an IDE. Do not duplicate either.
- The main-function runner has the same no-LSP requirement and stays in `plugin.xml`. `GoMainFunction` owns recognizing a parameterless, result-less `func main()` in `package main`; the gutter action runs `go run .` from that package's directory so the command includes all of its source files.
- `GoMainLineMarkerProvider` and `GoTestLineMarkerProvider` are plain `LineMarkerProvider`s, and there is no `RunConfigurationProducer`. Both of the platform's usual mechanisms are handed PSI elements to recognise, and a TextMate `.go` file is one leaf, so a contributor could place at most one arrow at line 1. The markers are placed by offset inside that leaf and the runner objects do the producer's job. Revisit only if the TextMate PSI changes.
- `GoTestEventTranslator` must keep using `nodeId`/`parentNodeId` with `isIdBasedTestTree`, and must keep deferring a test's start until its outcome arrives. Go interleaves parallel tests, so message order cannot carry the nesting; and whether `TestFoo` is a test or a suite is only decided by whether a subtest runs, while the SM runner fixes that when the node is started. Starting eagerly cannot be corrected without showing a phantom pass.
- Preserve every output-bearing `go test -json` event except the toolchain's structural frame lines. In particular, `fmt.Println`, `log.Println`, `t.Log`, benchmark output, and panic stacks belong to the test; a package failure must fail any test still open after a panic rather than treating it as interrupted.
- Build service messages through `ServiceMessageBuilder`, never by string concatenation. It escapes the attribute values, and a test's output routinely contains the characters that need it.
- `gopls` owns Go parsing, type checking, diagnostics, completion, navigation, and refactoring. The local Go toolchain owns save formatting through `gofmt` and optional `goimports`, and the same path adds the imports "Implement interface" needs; do not start a native Go PSI/type-system rewrite unless the roadmap explicitly calls for it.
- Context actions offered by `gopls` normally reach `Alt+Enter` through the platform LSP adapter; do not wrap them without first proving that request path misses the action. `refactor.rewrite.fillStruct` is the known exception: its dedicated intention requests that exact kind over the caret's line with an invoked trigger, then delegates resolution and edit application to `LspIntentionAction`. Local Go intentions are for operations the server does not offer, such as `GoAddKeyToTagsIntention`, and must use text rather than PSI while TextMate still parses each Go file as one leaf.
- Struct-tag completion is plugin-owned because `gopls` does not offer GoLand's tag-key entries. `GoStructTagCompletionContributor` must read the original document rather than completion's synthetic PSI copy, and its typed handler must retain explicit TextMate `.go` and native-plugin checks because that extension is global.
- "Implement interface" generates method text itself because `gopls` has no code action for it - a Go interface is satisfied structurally, so there is nothing for the server to offer. Keep the text handling in `GoLspMethodStubs`, which is unit-tested, rather than in the service.
- IntelliJ 2026.1.4 renamed `LspServerSupportProvider`, `LspServerDescriptor`, and `LspServerManager` to `LspIntegrationProvider`, `LspClientDescriptor`, and `LspClientManager`. The old names still compile against 262 but are deprecated; migration is follow-up work.
- Do not add a dependency on `com.intellij.modules.ultimate`; the LSP module is the only IU-specific dependency and it must remain optional.
- GoLand already ships native Go support. Do not claim GoLand compatibility or add overlapping integrations without first designing conflict handling.

## Documentation

Update the documentation as part of finishing a change, not as a separate task afterwards. A change
that alters what the plugin does, how it does it, or what it depends on is not done until the files
below say so. Keep it in the same commit as the code where the two are one change; a documentation
pass over work already committed is its own commit.

- `README.md` is the user-facing setup and status summary. Touch it for anything a user can see,
  as a status bullet and, when it needs explaining, a section of its own.
- `docs/FEATURES.md` is the current feature matrix; distinguish implemented foundation, target
  capabilities, and not-yet-implemented work. Every user-visible change lands here too, including
  moving a line out of "not yet implemented".
- `docs/ARCHITECTURE.md` describes ownership boundaries and lifecycle. Touch it for a new component
  or a responsibility moving between components.
- `docs/COMPATIBILITY.md` records supported platform/product constraints and risks. Touch it for a
  new platform API, a new external tool, or a new constraint or risk.
- `docs/DEVELOPMENT.md` records setup, commands, and testing expectations. Touch it for a new test,
  a new command, or a technique worth repeating.
- `docs/ROADMAP.md` records future Core Plus, managed toolchain, and GoLand parity work. Anything
  deliberately left for later belongs here rather than in a TODO in the code.
- This file carries the invariants a future change could undo without noticing, each stated as a
  rule with its reason attached. That is what most of the Architecture section is.

Record why, not just what. The reason a thing is done a particular way - a platform API that behaves
differently than it reads, a server that answers differently depending on what the client declares -
is the part that cannot be recovered from the code later, and is what these files are for. Prefer
correcting an existing sentence over appending a new one; a doc that accumulates only additions
stops being read.
