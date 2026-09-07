# Architecture

## Boundary

The plugin owns IntelliJ integration and process configuration. `gopls` owns Go parsing, type checking, package loading, diagnostics, completion, navigation, refactoring, and formatting.

```text
IntelliJ IDEA
    |
    | LSP4IJ adapters
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

- `GoLanguage`: identifies Go documents to IntelliJ and LSP4IJ.
- `GoFileType`: registers `.go` files.
- `GoLanguageServerFactory`: resolves the executable and creates an LSP4IJ process connection.
- `GoLspDiscovery`: searches configured and conventional executable locations.
- `GoLspSettingsState`: persists user configuration at application scope.
- `GoLspConfigurable`: exposes executable and command arguments in Settings.
- `RestartGoLspAction`: stops and starts the project server.

## Process Lifecycle

LSP4IJ creates a server when a mapped Go file requires it. The factory builds a command equivalent to:

```text
gopls serve
```

The working directory is the IntelliJ project base path. LSP4IJ handles JSON-RPC transport, document synchronization, server capabilities, diagnostics, and standard feature adapters.

## Why There Is No Native Go PSI

The historical Go plugin supplied a complete parser, PSI tree, stub indexes, type model, inspections, run configurations, and debugger. That implementation targets IntelliJ 2016.3 and pre-modern Go. Recreating it would duplicate `gopls` and would delay a usable plugin substantially.

Native PSI can be added later for IntelliJ-only features that LSP cannot provide, but it is not part of the initial architecture.

## Future Downloaded Server

Server installation should be added behind the existing discovery boundary. The intended order is:

1. Explicit configured server.
2. Managed downloaded server for the selected Go version.
3. `PATH` and Go SDK discovery.

The download implementation must be platform-aware, checksum-verified, cancellable, and isolated per version. It must never replace an explicitly configured executable.
