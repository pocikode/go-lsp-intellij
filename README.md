# Go LSP for IntelliJ IDEA

<!-- Plugin description -->

Free Go language support for IntelliJ IDEA Community powered by the installed `gopls` language server.

<!-- Plugin description end -->

This project is an LSP-based alternative for IntelliJ IDEA users who want modern Go editor support without purchasing GoLand. It uses [LSP4IJ](https://github.com/redhat-developer/lsp4ij) to connect IntelliJ to [gopls](https://pkg.go.dev/golang.org/x/tools/gopls).

## Current Status

The first milestone is the server integration foundation:

- Go filename mapping with IntelliJ's bundled TextMate syntax highlighting
- Installed `gopls` discovery
- Project-scoped `gopls` process startup over stdio
- LSP4IJ language mapping
- Persistent executable and argument settings
- Restart action
- LSP-backed folding, signature help, document symbols, and go-to-definition
- Optional GoLand-style format-on-save through `gofmt`, with optional `goimports` import organization

The remaining core editor capabilities, including diagnostics, completion, hover, references, rename, and code actions, come from the LSP4IJ and `gopls` integration and are being validated against the selected platform baseline.

## Requirements

- IntelliJ IDEA Community 2024.2 or newer
- Java 17 runtime for the plugin, Java 21 for building
- Go installed on the machine
- `gopls` installed and executable
- `gofmt` available from the Go installation

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

LSP4IJ also provides its own language-server console and tracing controls.

To format Go files automatically when saving, enable `Reformat Go files with gofmt` in `Settings | Tools | Actions on Save`. The action formats the current editor contents with `gofmt` before saving. `Organize imports with goimports` is enabled by default. When installed, `goimports` runs against a temporary file beside the source file so it can resolve the project module, remove unused imports, and create standard-library versus third-party import groups. If it is not installed, the action safely falls back to `gofmt` only.

## Product Compatibility

The initial target is IntelliJ IDEA Community. LSP4IJ is used because IntelliJ's built-in LSP API is not available to Community editions.

GoLand already includes JetBrains' native Go plugin. GoLand support is therefore a future compatibility project and may require detecting or disabling duplicate Go language registrations. This plugin is not currently positioned as a GoLand replacement.

## References

- [Architecture](docs/ARCHITECTURE.md)
- [Features](docs/FEATURES.md)
- [Compatibility](docs/COMPATIBILITY.md)
- [Development and testing](docs/DEVELOPMENT.md)
- [Roadmap](docs/ROADMAP.md)
