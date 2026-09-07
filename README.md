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
- Optional GoLand-style format-on-save through `gofmt`, with optional `goimports` import organization

Signature help, structure view, and call hierarchy are provided by the platform starting with IntelliJ IDEA 2025.3. See [docs/FEATURES.md](docs/FEATURES.md) for the full matrix.

## Requirements

- IntelliJ IDEA 2025.2.1 or newer (the standard IntelliJ IDEA download; no license required)
- Java 21 for building
- Go installed on the machine
- `gopls` installed and executable
- `gofmt` available from the Go installation

The 2025.2 Community Edition build does not contain the LSP module. The plugin installs there, but only format-on-save works. From 2025.3 there is a single IntelliJ IDEA distribution and LSP support is available to everyone.

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

Set `JAVA_HOME` to a JDK 21 installation, then run:

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

To format Go files automatically when saving, enable `Reformat Go files with gofmt` in `Settings | Tools | Actions on Save`. The action formats the current editor contents with `gofmt` before saving. `Organize imports with goimports` is enabled by default. When installed, `goimports` runs against a temporary file beside the source file so it can resolve the project module, remove unused imports, and create standard-library versus third-party import groups. If it is not installed, the action safely falls back to `gofmt` only.

## Product Compatibility

The target is the IntelliJ IDEA distribution 2025.2.1 and newer. The plugin declares an optional dependency on the `com.intellij.modules.lsp` module, which JetBrains ships in IntelliJ IDEA and the other commercial IDEs.

GoLand already includes JetBrains' native Go plugin. When that plugin is actually loaded, this plugin does not start `gopls`, so the two do not produce duplicate navigation results. A Go plugin that is installed but cannot load, for example without an Ultimate subscription, does not block `gopls`. GoLand support beyond that guard is a future compatibility project.

## References

- [Architecture](docs/ARCHITECTURE.md)
- [Features](docs/FEATURES.md)
- [Compatibility](docs/COMPATIBILITY.md)
- [Development and testing](docs/DEVELOPMENT.md)
- [Roadmap](docs/ROADMAP.md)
