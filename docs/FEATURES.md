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
- Optional GoLand-style format-on-save through local `gofmt`, configurable under Actions on Save
- Optional import organization through `goimports`

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
- Code vision (usage counts, authors), parameter name inlay hints, and GoLand's hyperlinking of
  route strings are separate features, not colours; they are absent regardless of the scheme.
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

## Not Yet Implemented

- Automatic `gopls` download
- Go version selection
- Go SDK/project model integration
- Run configurations
- `go test` integration
- Coverage
- Delve debugging
- Go-specific native inspections
- Native Go refactorings beyond what the platform LSP client provides
- GoLand conflict handling beyond skipping the server when the native plugin is loaded
