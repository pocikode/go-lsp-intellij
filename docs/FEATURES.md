# Features

## Available Foundation

- `.go` file mapping to the language server
- IntelliJ TextMate Go syntax highlighting, layered with `gopls` semantic tokens mapped to GoLand's colour keys
- Go icons for `.go` files and for `go.mod`, `go.sum`, `go.work` and `go.work.sum`
- `*_test.go` reported as test sources, which is what tints them green in the project view
- `gopls` stdio process integration through the IntelliJ LSP API
- Project-scoped server lifecycle
- Configurable executable path
- Configurable server arguments
- Missing-`gopls` notification
- Language-server status bar widget with stop and restart
- Restart action in the Tools menu
- Native Go plugin conflict suppression
- Go to declaration, Cmd/Ctrl+hover link styling, and show usages from a declaration ("Go to Declaration or Usages"), handled by the plugin through gopls definition/references
- Code vision above every Go declaration: usage count, code author, and "Implement interface"
- Optional GoLand-style format-on-save through local `gofmt`, configurable under Actions on Save
- Optional import organization through `goimports`
- Go test runner: run arrows in the gutter of a `*_test.go` file, a "Go Test" run configuration, and the platform's test tree fed from `go test -json`

## Code Vision Above A Declaration

GoLand shows three things above a Go declaration, and all three are here.

Nothing about them can come from the PSI. The bundled TextMate grammar that claims `.go` files
parses a whole file into a **single** leaf - not the run of token-sized leaves its highlighting lexer
produces - so there is no per-declaration element for the platform's own providers to hang anything
on. The declarations come from `textDocument/documentSymbol` instead.

That needs a capability the platform client does not declare. `gopls` tailors its answer to what the
client claims, and without `hierarchicalDocumentSymbolSupport` it replies with a flat list carrying
no signatures and no struct or interface members. `GoLspServerDescriptor` overrides
`clientCapabilities` to declare it, along with `workspace/symbol`.

### Usages

`N usages` from `textDocument/references`, counted for every top-level declaration and every method
declared on one - not for struct fields or interface methods, which is where GoLand draws the line
too. Clicking opens the platform's Show Usages popup, answered by `GoLspUsageSearcher` through the
`GoLspSymbol` target the entry carries, so the popup agrees with Find Usages.

The provider joins the platform's `references` settings group, so the **Usages** switch under
`Settings | Editor | Inlay Hints | Code Vision` governs it.

### Code Author

The last committer, read from `git blame` through `AnnotationProvider` and formatted the way the
platform formats it: `Name`, `Name *` when part of the range is uncommitted, `Name +2` when others
contributed, `Name +2 *`, or `new *` for code that was never committed. Clicking toggles the Git
annotations gutter through the same `Annotate` action the platform's hint uses.

The platform ships this vision, and the first implementation here tried to reuse it by registering a
`VcsCodeVisionLanguageContext` for TextMate. That cannot work: the platform walks the PSI asking the
context which elements are declarations, and one leaf per file is not something that can be pointed
at declarations. `GoLspCodeAuthors` reads the blame directly instead. The provider still joins the
platform's `vcs.code.vision` group, so the **Code author** switch governs it as usual.

### Implement Interface

Pinned above the declaration on a struct or named type, with GoLand's own icon
(`AllIcons.Actions.SuggestedRefactoringBulb`) and anchor (`Top`), both taken from GoLand's provider
rather than guessed. Interfaces are excluded: a Go interface is satisfied structurally, so methods
are only ever generated onto a concrete type.

`gopls` has no code action behind this, for the same structural reason, so the chooser and the
generation are the plugin's. The chooser queries `workspace/symbol` on each keystroke, seeded with
the enclosing `go.mod` module path because `gopls` answers an empty query with nothing. Methods are
built from the signatures `documentSymbol` reports for the chosen interface, following embedded
interfaces into their own files, and the body is GoLand's:

```go
func (r *Repo) Get(ctx context.Context, id string) (*V1, error) {
	//TODO implement me
	panic("implement me")
}
```

Methods the type already implements are skipped, and the receiver name and pointer-ness are taken
from its existing methods when it has any. `goimports` runs over the result to add whatever the new
signatures reference.

### How The Entries Reach The Screen

A `textDocument/references` call per declaration is far too slow to answer a highlighting pass
inline, so `GoLspCodeVisionService` computes everything in the background and the providers only read
its cache. Two consequences are visible:

- The entries appear a moment after a file opens, not instantly. A `gopls` answer arriving is not a
  PSI change, and the code vision pass skips a file whose PSI has not changed since it last ran, so
  the service drops that stamp before restarting the daemon; without it the entries would wait for
  the next keystroke.
- Usage counts and authors are keyed by declaration name rather than position, so an edit elsewhere
  in the file leaves them attached to the right declaration while only the ranges refresh.

### Known Remaining Differences

- Authors come from the file as last saved. `git blame` is indexed by the lines of the file on disk,
  so a document with unsaved edits keeps the authors from the last saved state rather than blaming
  lines that have since moved; they are re-read on the first refresh after a save.
- The committer is always the full name. The platform abbreviates it according to the annotation
  gutter's short-name setting, but that setting's class lives in a platform module the plugin does
  not compile against, and its default is the full name.
- Usage counts stop after the first 200 declarations in a file.
- A generic type's generated methods do not carry its type parameters.

## Colour Scheme Parity With GoLand

`gopls` keeps semantic tokens off unless asked, so the descriptor turns `semanticTokens` on and
`GoLspSemanticTokens` maps the tokens it returns.

The mapping targets GoLand's own attribute keys by external name - `GO_PACKAGE`,
`GO_TYPE_REFERENCE`, `GO_LOCAL_FUNCTION_CALL` and so on - rather than plugin-private names or the
platform's `DefaultLanguageHighlighterColors` constants directly. Three things then line up:

1. A scheme tuned for GoLand names those keys, and using them is the only way to honour it. Trash
   Panda, for one, redirects `GO_BUILTIN_FUNCTION_CALL` and `GO_LOCAL_FUNCTION_CALL` onto the
   platform defaults.
2. A scheme that says nothing about Go falls through to `colorSchemes/GoLspDarcula.xml` and
   `colorSchemes/GoLspLight.xml`, which carry GoLand's own values for the keys it colours
   explicitly. A scheme that does name a key still wins over these.
3. Anything left resolves through the fallback key, which is the same fallback GoLand's key declares.

Mapping onto `DefaultLanguageHighlighterColors` alone is not sufficient, and was the first attempt.
Most Go keys do resolve through their fallback, but `GO_PACKAGE` and `GO_TYPE_REFERENCE` carry
explicit colours that no fallback reproduces - which is why package qualifiers and type references
rendered plain grey and green instead of GoLand's olive and teal.

The keys chosen are the ones GoLand applies with its optional semantic highlighting off, which is
the default: every non-builtin type reference is `GO_TYPE_REFERENCE` whether it names a struct or an
interface, and struct members are not coloured apart from other locals.

Purely lexical tokens are deliberately left to the TextMate grammar, which already agrees with
GoLand and is more precise than the token stream: `gopls` reports one flat `comment` type for line
and block comments alike, and does not split escape sequences out of a string literal.

Two things the token stream cannot express are handled beside it, by position:

- `gopls` reports the path in `import "net/http"` as a `namespace` token, the same type and
  modifiers it uses for the `gin` in `gin.Engine`, so the mapping cannot help but colour it as a
  package. `GoLspImportPathFilter` drops that highlight when the token is bracketed by double
  quotes, which an import path always is and a qualifier never is.
- GoLand paints a struct tag in three pieces, dropping the key and its colon to the plain identifier
  colour while the backticks and quoted value keep the raw string colour. `gopls` sends the tag as
  one flat `string` token and the TextMate grammar scopes it as one `string.quoted.raw.go`, so
  `GoLspStructTagAnnotator` adds the split, over the ranges `GoStructTags` finds.

Document links are turned off. `gopls` returns a pkg.go.dev link for every import path, and the
platform client paints those with the scheme's hyperlink attributes, so the whole import block came
out underlined and recoloured; GoLand leaves import paths looking like the plain strings they are.

### Test Files In The Project View

`GoLspTestSourcesFilter` reports `*_test.go` as test sources. It sets no colour and declares no
scope: the platform's built-in "Tests" scope is a filtered package set over
`TestSourcesFilter.isTestSources`, and File Colors paints that scope Green already, taking the shade
from the active theme. Go has no test source root for the platform to find on its own, so without a
filter nothing is ever in that scope. GoLand reaches the same green through the same hook and the
same single rule.

The filter also decides what counts as a test for Find Usages scopes and for inspections that skip
tests, which is the intended meaning for a Go test file.

### Known Remaining Differences

- Semantic tokens carry no identifier text, so exported and unexported names cannot be told apart.
  This matches GoLand at its default setting, but not GoLand with semantic highlighting switched on,
  where struct members and functions split by export.
- Method receivers arrive as ordinary parameters, so `GO_METHOD_RECEIVER` is never used.
- Parameter name inlay hints and GoLand's hyperlinking of route strings are separate features, not
  colours; they are absent regardless of the scheme. Code vision is implemented - see above.
- Ctrl/Cmd+click from an import path to pkg.go.dev is gone with document links; navigation into the
  package source through `gopls` is unaffected.

## Editor Features From The Platform LSP Client

The IntelliJ LSP client implements these capabilities itself; the plugin only declares the server. Availability depends on the IDE version:

| IDE version | Capabilities |
|-------------|--------------|
| 2025.2 | Diagnostics, quick-fixes, completion, go to type declaration, hover, intention actions and code actions, whole-file formatting, find usages, semantic highlighting, execute command, workspace edits, document links, pull diagnostics, inlay hints, folding |
| 2025.3 | Adds server-initiated progress, highlight usages in file, go to symbol, structure view and breadcrumbs, signature help, selection range, call hierarchy, type hierarchy |
| 2026.1 | Adds range formatting, code lens, optimize imports, rename, on-type formatting |

Each capability also requires `gopls` to advertise it. The platform's go-to-declaration support is disabled for Go on purpose; the plugin implements navigation itself (see the foundation list above).

## Running Tests

The test runner is built on `go test -json`, which reports every test's start, output, outcome and
duration as one JSON object per line. That is the whole reason a GoLand-like tree is possible here
without a Go parser: `GoTestEventTranslator` turns those events into the service messages the
platform's SM test runner builds its tree from, and nothing in the path has to understand Go.

Two decisions in that translation are worth knowing:

- Nodes carry `nodeId`/`parentNodeId` rather than relying on message order. Go interleaves the
  events of parallel tests, and a stack-shaped protocol would nest them wrongly.
- A test's start is held back until its outcome is known. A node is created as either a test or a
  suite by the message that starts it, and whether `TestFoo` is a suite is only decided by whether
  `TestFoo/case` ever runs. The cost is that a test appears in the tree when it finishes; package
  progress and package-level output stay live.

`go test`'s own `=== RUN` and `--- PASS` lines are kept out of a test's output pane. Recent Go
versions label exactly those lines `"OutputType":"frame"`, which is used when present; a pattern is
the fallback for a toolchain that does not report the field, and is dropped for the rest of the run
as soon as one event shows that it does.

### The Gutter Arrows

The arrows are a plain `LineMarkerProvider`, not the `RunLineMarkerContributor` that normally puts a
run arrow in the gutter, and there is no `RunConfigurationProducer`. Both of those are handed PSI
elements to recognise, and the bundled TextMate grammar parses a whole `.go` file into a single PSI
leaf - the same reason the code vision is plugin-owned. `GoTestFunctions` finds the test
declarations in the file's text instead, and the markers are placed by offset inside that leaf.

Reading the text rather than asking `gopls` is deliberate twice over: the arrows are there the
moment a file opens, and they are there in a build with no LSP module at all, where `go test` runs
perfectly well. Everything in this feature is registered in `plugin.xml` rather than `go-lsp.xml`
for that reason.

### Navigation From The Tree

`GoTestLocator` resolves a node back to its source. The tree only knows the package's import path
and the test's name, and turning an import path into a directory is a string operation once `go.mod`
has been read: the module path is the prefix every package under it shares. A subtest resolves to
its parent test function, because Go derives a subtest's name from the string passed to `t.Run`,
with spaces replaced by underscores, so there is usually nothing in the file to match.

## Not Yet Implemented

- Automatic `gopls` download
- Go version selection
- Go SDK/project model integration
- Coverage
- Delve debugging
- Debugging a test (the gutter offers Run only)
- Go-specific native inspections
- Native Go refactorings beyond what the platform LSP client provides
- GoLand conflict handling beyond skipping the server when the native plugin is loaded
