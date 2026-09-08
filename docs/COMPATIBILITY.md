# Compatibility

## Baseline

- IntelliJ Platform: 2026.2 (compiled against the locally installed IntelliJ IDEA 2026.2.2)
- Since-build: `262.10315`
- Product target: IntelliJ IDEA
- Build JDK: 25, from the installed IDE runtime
- Plugin bytecode: Java 25
- LSP client: IntelliJ LSP API (`com.intellij.modules.lsp`)
- Language server: installed `gopls`
- Program runner: installed `go`, through `go run`
- Test runner: installed `go`, through `go test -json`

## IntelliJ Editions

The LSP API ships in the IntelliJ IDEA (Ultimate) distribution and in the other commercial JetBrains IDEs. Since 2025.2.1 it works without a paid license, and since 2025.3 there is a single IntelliJ IDEA distribution, so the plugin is compiled against IU.

The current artifact targets build 262 and does not install on older IDE releases. The dependency on `com.intellij.modules.lsp` remains optional so platform-independent components stay isolated from the LSP classes.

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

Go TODO items use the platform's `IndexPatternBuilder` extension. The interface is intended for
language integrations but lives in `com.intellij.psi.impl.search`, so it is a platform compatibility
risk and must remain covered by compilation, plugin structure verification, and Plugin Verifier.
The bundled TextMate `PlainTextTodoIndexer` is deliberately retained: replacing its shared
`textmate` registration would affect every TextMate-backed language and require the internal
`TodoIndexEntry` API. It may over-index a TODO-looking string as a candidate, but the Go builder's
comment ranges prevent that candidate from becoming a visible item.

Struct-tag suggestions use the stable platform completion APIs (`CompletionContributor`,
`LookupElementBuilder`, `TypedHandlerDelegate`, `AutoPopupController`, and `CompletionConfidence`).
The typed handler is global, so it must retain explicit TextMate `.go` and native-plugin checks;
the contributor's language registration alone does not protect that path.

The platform client declares no `documentSymbol` or `workspace/symbol` client capabilities, so
`GoLspServerDescriptor` overrides `clientCapabilities` to add them. If a future platform release
declares them itself, that override should be re-checked rather than removed blindly - `gopls`
changes what it returns based on what is declared.

## Platform APIs Behind The Runners

None of these need the LSP module; all of them ship in every IntelliJ IDEA build:

- `SMTRunnerConsoleProperties`, `SMCustomMessagesParsing`, `OutputToGeneralTestEventsConverter`,
  `SMTestRunnerConnectionUtil` and `ServiceMessageBuilder`, for the test tree.
- `isIdBasedTestTree` with `nodeId`/`parentNodeId` attributes, which is what makes the tree
  independent of the order Go reports parallel tests in.
- `SMTestLocator` and a `Location` subclass of the plugin's own. `PsiLocation` navigates to the
  element it is given, and a TextMate `.go` file has exactly one, so every test would open at the
  top of its file; the line is known, so the descriptor is built from it directly.
- `AbstractRerunFailedTestsAction` and its `MyRunProfile`, for rerun-failed.
- `RunLineMarkerContributor.getTestStateIcon`, for the standard persisted passed/failed gutter icon
  even though TextMate's single-leaf PSI requires the markers themselves to use `LineMarkerInfo`.
- `LineMarkerProvider`, used instead of `RunLineMarkerContributor`, and no `RunConfigurationProducer`
  at all. Both of those are handed PSI elements to recognise; see `docs/ARCHITECTURE.md`.
- `com.google.gson`, which the platform bundles and lsp4j already depends on, for parsing the
  `go test -json` stream.

## Go Toolchain For Programs And Tests

The main-function runner shells out to `go run .` from the package directory. The editable
configuration can use another package or file target and keeps Go tool arguments before that target,
with program arguments after it. The runner passes no flags beyond those supplied by the user.

The test runner shells out to `go test -json -v`. Both flags have been available since Go 1.10, and the
event fields used - `Action`, `Package`, `Test`, `Output`, `Elapsed` - have been stable since. A
recent addition is `OutputType`, which labels the toolchain's own `=== RUN` and `--- PASS` lines
(present in go 1.27, the version this was verified against); it is used when present and a pattern
is the fallback when it is not, so both old and new toolchains behave the same.

No flag newer than `-json` is passed. `-fullpath` would make failure locations absolute and remove
the need to resolve a bare filename through the filename index, but it does not exist before Go
1.21 and an unknown flag makes `go test` fail outright.

## API Names

IntelliJ 2026.1.4 renamed the LSP API classes (`LspServerSupportProvider` to `LspIntegrationProvider`, `LspServerDescriptor` to `LspClientDescriptor`, `LspServerManager` to `LspClientManager`). The old names remain binary-compatible in 2026.2 but produce deprecation warnings; migrating them is follow-up work.

## GoLand

GoLand already ships native Go support. Registering another Go language implementation can produce duplicate file types, completion providers, inspections, and actions. When the native Go plugin (`org.jetbrains.plugins.go`) is loaded, the server support provider does not start `gopls`, and the local struct-tag completion and intention also stand down. The check uses `PluginManagerCore.isLoaded`, not `isPluginInstalled`, because the Go plugin can be installed yet fail to load when the Ultimate module is disabled without a subscription. GoLand is not an initial supported product beyond that guard.

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
- TODO discovery depends on the platform continuing to run `IndexPatternBuilder` after its
  file-based TODO index finds a candidate. The builder is ordered after TextMate's empty builder so
  its Go lexer supplies the exact ranges.
- The gutter arrows find test functions by matching the file's text. A `func TestX(` written at the
  start of a line inside a raw string literal would be matched; nothing else in Go's grammar can
  produce a false positive there.
- Table-case gutter actions require a static name: a literal `t.Run` argument, or a string field in
  a local keyed or positional `[]struct` table selected by a range loop. Cases produced by function calls or
  arbitrary expressions still appear in the runtime test tree but cannot be selected from source.
- The main-function arrow also reads the file text. A file whose raw string contains both a
  line-shaped `package main` and `func main() {` can produce a false marker.
- A test appears in the test tree when it finishes rather than when it starts. See
  `docs/ARCHITECTURE.md` for why the alternative is worse.
- `go test` reports a failure's location by base filename, resolved through the filename index. Two
  files with the same name in different packages can send the link to the wrong one.
- Reference counts cost one `textDocument/references` call per declaration. They are cached and
  computed in the background, capped at 200 declarations per file and four concurrent requests, but
  a very large workspace still makes them slow to appear.
