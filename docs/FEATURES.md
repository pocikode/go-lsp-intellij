# Features

## Available Foundation

- `.go` file mapping to the language server
- IntelliJ TextMate Go syntax highlighting
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
