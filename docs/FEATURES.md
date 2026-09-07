# Features

## Available Foundation

- `.go` LSP filename mapping
- IntelliJ TextMate Go syntax highlighting
- `gopls` stdio process integration
- LSP4IJ language mapping
- Project-scoped server lifecycle
- Configurable executable path
- Configurable server arguments
- Restart action
- Go to definition through LSP4IJ, with native Go plugin conflict suppression
- LSP folding
- LSP signature help
- LSP document symbols
- Optional GoLand-style format-on-save through local `gofmt`, configurable under Actions on Save
- Optional import organization through `goimports`

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
- Whole-file and range formatting through LSP4IJ, plus whole-file `gofmt` format-on-save
- Import organization on save through optional `goimports`
- Semantic tokens (provided by LSP4IJ when supported by `gopls`)
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
