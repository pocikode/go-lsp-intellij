# Go LSP for IntelliJ IDEA

<!-- Plugin description -->

Free Go language support for IntelliJ IDEA Community powered by the installed `gopls` language server.

<!-- Plugin description end -->

This project is an LSP-based alternative for IntelliJ IDEA users who want modern Go editor support without purchasing GoLand. It uses [LSP4IJ](https://github.com/redhat-developer/lsp4ij) to connect IntelliJ to [gopls](https://pkg.go.dev/golang.org/x/tools/gopls).

## Current Status

The first milestone is the server integration foundation:

- Go file type registration
- Installed `gopls` discovery
- Project-scoped `gopls` process startup over stdio
- LSP4IJ language mapping
- Persistent executable and argument settings
- Restart action
- LSP-backed folding, signature help, and document symbols

The remaining core editor capabilities, including diagnostics, completion, navigation, rename, formatting, and code actions, come from the LSP4IJ and `gopls` integration and are being validated against the selected platform baseline.

## Requirements

- IntelliJ IDEA Community 2024.2 or newer
- Java 17 runtime for the plugin, Java 21 for building
- Go installed on the machine
- `gopls` installed and executable

Install `gopls` with:

```sh
go install golang.org/x/tools/gopls@latest
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

LSP4IJ also provides its own language-server console and tracing controls.

## Product Compatibility

The initial target is IntelliJ IDEA Community. LSP4IJ is used because IntelliJ's built-in LSP API is not available to Community editions.

GoLand already includes JetBrains' native Go plugin. GoLand support is therefore a future compatibility project and may require detecting or disabling duplicate Go language registrations. This plugin is not currently positioned as a GoLand replacement.

## References

- [Architecture](docs/ARCHITECTURE.md)
- [Features](docs/FEATURES.md)
- [Compatibility](docs/COMPATIBILITY.md)
- [Development and testing](docs/DEVELOPMENT.md)
- [Roadmap](docs/ROADMAP.md)
