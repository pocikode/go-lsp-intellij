# Architecture

## Boundary

The plugin owns IntelliJ integration and process configuration. `gopls` owns Go parsing, type checking, package loading, diagnostics, completion, navigation, and refactoring. The local Go toolchain owns save formatting through `gofmt` and optional import organization through `goimports`.

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

- `.go` filename mapping: associates Go files with the LSP server without replacing IntelliJ's TextMate file handling.
- `GoLanguageServerFactory`: resolves the executable and creates an LSP4IJ process connection.
- `GoLspDiscovery`: searches configured and conventional executable locations.
- `GoLspSettingsState`: persists user configuration at application scope.
- `GoLspConfigurable`: exposes executable and command arguments in Settings.
- `RestartGoLspAction`: stops and starts the project server.
- `GoFormatOnSave`: registers the project-level Actions on Save integration and formats the current in-memory `.go` document with `gofmt`, then optionally `goimports`.
- `GoFormatOnSaveState`: persists the format-on-save choice per project.

## Process Lifecycle

LSP4IJ creates a server when a mapped Go file requires it. The filename mapping preserves the IntelliJ TextMate Go grammar while LSP4IJ provides the navigation adapters. The factory builds a command equivalent to:

```text
gopls serve
```

The working directory is the IntelliJ project base path. LSP4IJ handles JSON-RPC transport, document synchronization, server capabilities, diagnostics, and standard feature adapters.

When `Reformat Go files with gofmt` is enabled under `Settings | Tools | Actions on Save`, the save action sends the current in-memory document through `gofmt` and applies its stdout before the platform saves the document. If `Organize imports with goimports` is enabled in `Settings | Tools | Go LSP`, the formatted text is written to a temporary file beside the source file and passed to `goimports`. Running from the source directory lets `goimports` resolve the module, remove unused imports, and group standard-library and third-party imports before the result is copied back. Formatting does not depend on the LSP server being started.

## Why There Is No Native Go PSI

The historical Go plugin supplied a complete parser, PSI tree, stub indexes, type model, inspections, run configurations, and debugger. That implementation targets IntelliJ 2016.3 and pre-modern Go. Recreating it would duplicate `gopls` and would delay a usable plugin substantially.

Native PSI can be added later for IntelliJ-only features that LSP cannot provide, but it is not part of the initial architecture.

## Future Downloaded Server

Server installation should be added behind the existing discovery boundary. The intended order is:

1. Explicit configured server.
2. Managed downloaded server for the selected Go version.
3. `PATH` and Go SDK discovery.

The download implementation must be platform-aware, checksum-verified, cancellable, and isolated per version. It must never replace an explicitly configured executable.
