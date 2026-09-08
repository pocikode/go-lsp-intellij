# Go LSP for IntelliJ IDEA

<!-- Plugin description -->

Free Go language support for IntelliJ IDEA powered by the installed `gopls` language server and the IntelliJ LSP API.

<!-- Plugin description end -->

This project is an LSP-based alternative for IntelliJ IDEA users who want modern Go editor support without purchasing GoLand. It uses the built-in [IntelliJ LSP API](https://plugins.jetbrains.com/docs/intellij/language-server-protocol.html) to connect IntelliJ to [gopls](https://pkg.go.dev/golang.org/x/tools/gopls). Since IntelliJ IDEA 2025.2 that API works for every user, with or without a paid license.

## Current Status

The first milestone is the server integration foundation:

- Go filename mapping with IntelliJ's bundled TextMate syntax highlighting, layered with `gopls` semantic tokens mapped to GoLand's colour keys so one theme looks the same in both IDEs
- Go icons for `.go` files and the `go.mod`, `go.sum` and `go.work` manifests
- `*_test.go` marked as test sources, so the project view tints them green like GoLand
- Installed `gopls` discovery, with a notification when it is missing
- Project-scoped `gopls` process startup over stdio
- Persistent executable and argument settings
- Language-server status bar widget and restart action
- LSP-backed diagnostics, completion, hover, go-to-definition, find usages, code actions, formatting, folding, and inlay hints
- Cmd/Ctrl+hover link styling, Cmd/Ctrl+click navigation, and usages popup on declarations, as in GoLand
- GoLand's code vision above every Go declaration: a usage count, the last committer, and "Implement interface"
- Go struct-tag completion for `asn1`, `bson`, `json`, `xml`, and `yaml`, plus a plugin-owned **Add tag key to all fields** entry and the matching editor intention
- TODO tool-window support for `TODO`, `FIXME`, and custom TODO patterns in Go comments, including
  the Project and Current File views and navigation to the matching source
- GoLand-style format-on-save through `gofmt`, enabled by default with optional `goimports` import organization
- A GoLand-style run arrow on `func main()`, backed by an editable "Go Run" configuration and `go run .`
- GoLand's test runner: gutter icons that retain the last pass/fail state, per-case actions for statically named table tests, a "Go Test" run configuration, and the platform's test tree built from `go test -json`, with subtests nested, failures navigable, and rerun-failed

Signature help, structure view, and call hierarchy are provided by the platform starting with IntelliJ IDEA 2025.3. See [docs/FEATURES.md](docs/FEATURES.md) for the full matrix.

## Code Vision

Above each declaration the plugin shows what GoLand shows there:

- **N usages**, counted with `textDocument/references`. Clicking opens the Show Usages popup.
- **The last committer**, from `git blame`. Clicking toggles the Git annotations gutter.
- **Implement interface** on a struct or named type, above the declaration. Clicking opens a
  searchable list of interfaces; picking one writes the missing methods onto the type and runs
  `goimports` over the result.

Position and visibility follow `Settings | Editor | Inlay Hints | Code Vision`. The usage count sits
in the platform's **Usages** group and the committer in its **Code author** group, so the switches
already there govern both; "Implement interface" has a group of its own and is pinned above the
declaration, as in GoLand.

The entries appear a moment after a file opens rather than instantly: they are computed from `gopls`
in the background, not during highlighting. Authors come from the file as last saved, so a file with
unsaved edits keeps the previous names until it is saved again.

## Context Actions

Typing an empty tag (two backticks) after a struct field opens completion with `asn1`, `bson`,
`json`, `xml`, and `yaml`. Choosing a key inserts a snake-case value for that field. The first entry,
**Add tag key to all fields**, asks for a key and applies it to every eligible field in the enclosing
struct while preserving existing tags.

Press `Alt+Enter` in a Go file to open the normal IntelliJ intention menu. **Fill all fields** asks
`gopls` for its dedicated struct rewrite: on a struct literal it inserts every missing keyed field
with the correct zero value. **Add key to tags** is available inside a struct declaration; enter a
key such as `json`, `xml`, or `db` and the action adds snake-case values to fields that do not
already have that key while preserving existing tags.

## Running Programs

A `func main()` declaration in `package main` gets a green run arrow. Clicking it creates or reuses a
"Go Run" configuration and runs `go run .` from the file's directory. Running the package rather
than only the open file includes the other `.go` files that make up the command.

`Run | Edit Configurations | Go Run` exposes the working directory, package or file target, Go tool
arguments, program arguments, and environment variables. Output and process controls use the normal
Run tool window.

## Running Tests

`*_test.go` files get GoLand-style gutter actions: one beside every `func TestXxx`, one on the
package clause that runs the whole file, and one beside each statically discoverable `t.Run` case.
This includes literal names and the usual keyed or positional table shape (`tests := []struct{...}` followed by
`for _, tt := range tests` and `t.Run(tt.name, ...)`). Clicking a case builds an exact `-run`
pattern for that subtest. After a run, the marker retains IntelliJ's standard green passed or red
failed icon from the test history.

The tree comes from `go test -json`, so it is what the toolchain actually reports:

- Each package is a suite, each test a node under it, and a test that calls `t.Run` becomes a suite
  of its own with a node per subtest. Table-driven tests group the way they do in GoLand.
- A failure carries the test's own output, and the `foo_test.go:12` in front of it is a link to
  that line.
- Clicking a node opens the table name or literal that declares it when that name is statically
  discoverable, and otherwise opens its parent test function.
- Stopping a run leaves nothing spinning; unfinished tests are marked as not run rather than passed.
- Test logging is verbose for passed, failed, skipped, and benchmark cases; only `go test`'s own
  structural `=== RUN` and `--- PASS`/`FAIL` lines are removed from the output pane.
- `fmt.Println`, `log.Println`, `t.Log`, and panic stack traces are attached to their test. A panic
  marks the test failed even when it terminates the process before the usual per-test outcome event.

A test appears in the tree when it finishes rather than when it starts. Whether `TestFoo` is a test
or a suite of subtests is not knowable until a subtest runs, so the node is created once the answer
is in. Package-level progress and output stay live.

`Run | Edit Configurations | Go Test` shows the same thing as an editable command line - working
directory, package pattern, `-run` pattern, extra `go test` flags such as `-race`, and environment
variables - so anything the gutter generated can be adjusted by hand.

The program and test runners need only the `go` executable. They work in an IntelliJ build with no
LSP module, where the editor integration cannot run.

## Requirements

- IntelliJ IDEA 2026.2.2 or newer
- The Java 25 runtime bundled with IntelliJ IDEA for building
- Go installed on the machine
- `gopls` installed and executable
- `gofmt` available from the Go installation

The plugin now targets the installed IntelliJ IDEA 2026.2 line. Older IDE builds reject it.

Install `gopls` with:

```sh
go install golang.org/x/tools/gopls@latest
```

Install `goimports` if import organization on save is wanted:

```sh
go install golang.org/x/tools/cmd/goimports@latest
```

The plugin does not download `gopls` yet. Automatic installation and selecting a Go version are planned features.

## Development

The build uses the Java 25 runtime inside `/Applications/IntelliJ IDEA.app`. Override the IDE path
with `-PplatformPath=/path/to/IntelliJ IDEA.app`, then run:

```sh
./gradlew buildPlugin
./gradlew runIde
./gradlew verifyPlugin
```

The plugin distribution is written to `build/distributions`.

## Configuration

Open `Settings | Tools | Go LSP`:

- Leave `gopls executable` empty to discover `gopls` from `PATH`, `GOROOT/bin`, `GOPATH/bin`, or `~/go/bin`.
- Set an explicit executable path when multiple Go installations are present.
- Arguments default to `serve`.
- Enable or disable `Organize imports with goimports`.

The language-server widget in the status bar shows the `gopls` state and offers stop and restart actions. `Tools | Restart Go Language Server` restarts it as well. Enable `#com.intellij.platform.lsp` in `Help | Diagnostic Tools | Debug Log Settings` to trace LSP traffic.

`Reformat Go files with gofmt` is enabled automatically for each project under `Settings | Tools | Actions on Save`; clear the checkbox there to opt out. The action formats the current editor contents with `gofmt` before saving. `Organize imports with goimports` is enabled by default. When installed, `goimports` runs against a temporary file beside the source file so it can resolve the project module, remove unused imports, and create standard-library versus third-party import groups. If it is not installed, the action safely falls back to `gofmt` only.

## Product Compatibility

The target is IntelliJ IDEA 2026.2.2 and newer. The plugin declares an optional dependency on the `com.intellij.modules.lsp` module.

GoLand already includes JetBrains' native Go plugin. When that plugin is actually loaded, this plugin does not start `gopls`, so the two do not produce duplicate navigation results. A Go plugin that is installed but cannot load, for example without an Ultimate subscription, does not block `gopls`. GoLand support beyond that guard is a future compatibility project.

## References

- [Architecture](docs/ARCHITECTURE.md)
- [Features](docs/FEATURES.md)
- [Compatibility](docs/COMPATIBILITY.md)
- [Development and testing](docs/DEVELOPMENT.md)
- [Roadmap](docs/ROADMAP.md)
