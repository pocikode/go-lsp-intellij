# Features

## Available Foundation

- `.go` file registration
- `gopls` stdio process integration
- LSP4IJ language mapping
- Project-scoped server lifecycle
- Configurable executable path
- Configurable server arguments
- Restart action
- LSP folding
- LSP signature help
- LSP document symbols

## Core Editor Target

These are the first user-facing capabilities to validate and document as the integration matures:

- Diagnostics
- Completion
- Hover documentation
- Go to definition
- Go to type definition
- Find references
- Rename
- Code actions and quick fixes
- Whole-file and range formatting
- Organize imports
- Semantic tokens
- Folding
- Document symbols and structure view
- Signature help
- Call hierarchy

Most of these are LSP capabilities. Availability depends on the `gopls` version, LSP4IJ version, and IntelliJ platform version.

## Not Yet Implemented

- Automatic `gopls` download
- Go version selection
- Go SDK/project model integration
- Run configurations
- `go test` integration
- Coverage
- Delve debugging
- Go-specific native inspections
- Native Go refactorings beyond LSP rename/code actions
- GoLand conflict handling
