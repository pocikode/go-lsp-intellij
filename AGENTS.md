# Agent Instructions

## Current Status

- This is an IntelliJ IDEA plugin scaffold, not a GoLand replacement yet.
- The implemented foundation is Go filename mapping with TextMate syntax highlighting plus GoLand-matched `gopls` semantic token colouring, Go file icons, green test files in the project view, installed `gopls` discovery with a missing-server notification, startup through the IntelliJ LSP API over stdio, persistent `gopls` settings, a status bar widget, a restart action, and native Go plugin conflict suppression.
- GoLand's code vision above a Go declaration is implemented: a usage count, the last committer, and "Implement interface". All three are built from `gopls` rather than the PSI, and all three are plugin-owned rather than the platform's - see the Architecture section for why that is not a choice.
- Editor features (diagnostics, completion, hover, navigation, references, code actions, formatting, folding, inlay hints) come from the platform LSP client and `gopls`; which ones exist depends on the IDE version. Run/test support, debugging, automatic `gopls` download, and Go-version selection are roadmap work. Format-on-save is implemented through local `gofmt`, with optional `goimports` import organization.
- Read `docs/FEATURES.md` and `docs/ROADMAP.md` before describing or extending feature coverage. See the Documentation section for what to update once it has changed.

## Build

- The build targets the IntelliJ IDEA (Ultimate) distribution `2025.2.6` / since-build `252.25557`, uses the IntelliJ LSP API, compiles plugin bytecode for Java 21, and requires a Java 21 JDK to build.
- The plugin is compiled against IU because the LSP API only ships in that distribution. Since 2025.2.1 the API works without a paid license, and since 2025.3 there is only one IntelliJ IDEA distribution.
- Set `JAVA_HOME` to Java 21 before running Gradle. The repository wrapper delegates to `GRADLE_HOME` or a system `gradle`; the configured Gradle version is `9.7.1`.
- `gopls` is an external runtime prerequisite. The plugin currently does not download it; use an installed executable or configure its full path in `Settings | Tools | Go LSP`.
- Useful commands:
  - `./gradlew test` runs the JUnit 5 tests under `src/test/kotlin`.
  - `./gradlew buildPlugin` builds `build/distributions/go-lsp-intellij-<version>.zip`.
  - `./gradlew runIde` launches a development IntelliJ sandbox.
  - `./gradlew verifyPluginStructure` checks the plugin descriptor and archive structure quickly.
  - `./gradlew verifyPlugin` runs Plugin Verifier against supported IDEA builds and can download several large IDE distributions; run it for compatibility changes, not every edit.
- The normal verification order is `test`, `buildPlugin`, `verifyPluginStructure`, then `verifyPlugin` when platform/API compatibility needs checking.

## Architecture

- Keep IntelliJ integration in `src/main/kotlin/com/github/pocikode/go_lsp_intellij` and plugin registrations in `src/main/resources/META-INF/plugin.xml`.
- Everything that references `com.intellij.platform.lsp` must be registered in `META-INF/go-lsp.xml`, which is loaded through the optional `com.intellij.modules.lsp` dependency. `plugin.xml` must stay loadable without the LSP module.
- `GoLspServerSupportProvider` decides when to start a server; `GoLspServerDescriptor` owns the `gopls` command line, file mapping, and client capabilities; `GoLspSupport` owns the Go-file and native-plugin checks; `GoLspDiscovery` owns executable lookup; `GoLspSettingsState` owns persisted application settings; `GoLspRequests` owns synchronous `gopls` requests made outside the platform client and `GoLspSymbolRequests` the suspending ones; `GoLspFormatting` owns every `gofmt`/`goimports` invocation; do not duplicate these responsibilities.
- Navigation is plugin-owned: `GoLspReferenceProvider` (references, hover styling), `GoLspDeclarationProvider` (declarations), `GoLspSymbol` (navigatable symbol + search target), `GoLspUsageSearcher` (usages). `GoLspServerDescriptor` disables the platform's go-to-definition support so the built-in reference provider cannot duplicate targets; do not re-enable it without removing `GoLspReferenceProvider`. It also disables document links, which would otherwise underline every import path as a pkg.go.dev hyperlink. Keep other editor features on the platform client where it offers them.
- The code vision is plugin-owned out of necessity, not preference. The bundled TextMate grammar parses a whole `.go` file into a **single** PSI leaf - its highlighting lexer produces token-sized pieces, its parser does not - so nothing that finds declarations by walking the PSI can work for Go here. That rules out the platform's own usage-count provider and its `VcsCodeVisionLanguageContext` extension point for the code author; do not reach for either again without first checking whether the TextMate PSI has changed. Declarations come from `textDocument/documentSymbol`.
- `GoLspServerDescriptor` must keep declaring the `documentSymbol` (`hierarchicalDocumentSymbolSupport`) and `workspace/symbol` client capabilities. The platform client declares neither, and `gopls` downgrades its answer to a flat list with no signatures and no members when they are missing, which silently empties the code vision and "Implement interface".
- `GoLspCodeVisionService` owns all code vision state. Providers must only read its cache: a `textDocument/references` call per declaration cannot be made from a highlighting pass. When publishing, it must keep dropping the pass's PSI stamp through `ModificationStampUtil` before restarting the daemon - a server answer is not a PSI change, and without that the entries wait for the next keystroke. Counts and authors are keyed by declaration name, not position, deliberately.
- `GoLspCodeAuthors` reads `git blame` from the file on disk, so authors are only recomputed for a saved document. Do not blame an unsaved document; the line numbers no longer line up.
- Highlighting is split: the bundled TextMate grammar owns lexical tokens, and `GoLspSemanticTokens` owns the semantic ones. Return null from the mapping rather than guessing, so TextMate keeps the token. `GoLspServerDescriptor` must keep `semanticTokens` enabled in the `gopls` settings map; `gopls` defaults it to off.
- Two Go colouring rules cannot be expressed as a token mapping and live beside it: `GoLspImportPathFilter` removes the package colour from import paths (gopls reports them as `namespace`, like a qualifier), and `GoLspStructTagAnnotator` splits struct tag keys out of the raw string. Both work by position, which `getTextAttributesKey` does not get.
- Project view parity goes through platform hooks rather than custom rendering: `GoLspFileIconProvider` for icons and `GoLspTestSourcesFilter` for the green test files, the latter by joining the built-in "Tests" scope rather than declaring a colour. Both stand down when the native Go plugin is loaded.
- The keys live in `GoLspColors`, which must stay free of `com.intellij.platform.lsp` so extensions outside `go-lsp.xml` can use it. The mapping must name GoLand's `GO_*` attribute keys, not `DefaultLanguageHighlighterColors` constants: `GO_PACKAGE` and `GO_TYPE_REFERENCE` have explicit colours in GoLand's scheme files that no fallback key reproduces. `colorSchemes/GoLsp*.xml` supply those values for schemes that do not set them; a scheme that names a key wins over them. This is the one place the plugin deliberately shares names with the native Go plugin, so a GoLand-tuned theme applies here too. Read `docs/FEATURES.md` before changing a key choice.
- `gopls` owns Go parsing, type checking, diagnostics, completion, navigation, and refactoring. The local Go toolchain owns save formatting through `gofmt` and optional `goimports`, and the same path adds the imports "Implement interface" needs; do not start a native Go PSI/type-system rewrite unless the roadmap explicitly calls for it.
- "Implement interface" generates method text itself because `gopls` has no code action for it - a Go interface is satisfied structurally, so there is nothing for the server to offer. Keep the text handling in `GoLspMethodStubs`, which is unit-tested, rather than in the service.
- Use the 2025.2 API names (`LspServerSupportProvider`, `LspServerDescriptor`, `LspServerManager`). IntelliJ 2026.1.4 renamed them to `LspIntegrationProvider`, `LspClientDescriptor`, and `LspClientManager`; the old names still work there but are deprecated. Switch only when the since-build moves to 261.
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
