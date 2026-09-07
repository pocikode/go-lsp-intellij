# Roadmap

## Phase 1: Core Editor

- Stabilize LSP4IJ integration.
- Validate diagnostics, completion, hover, navigation, references, rename, formatting, imports, symbols, and code actions.
- Add disk-backed integration tests.
- Improve missing-server notifications.

## Phase 2: Core Plus

- Go SDK and workspace discovery.
- `go.mod` and multi-module awareness.
- Run configurations.
- `go test`, benchmarks, and subtests.
- `go fmt`, `goimports`, `go vet` actions.
- Coverage integration.
- Improved status and project diagnostics.

## Phase 3: Managed Toolchains

- Download `gopls` by platform and architecture.
- Verify downloads with checksums.
- Select and install Go versions.
- Keep explicit user-configured executables authoritative.
- Provide rollback and update controls.

## Phase 4: GoLand Parity Attempt

- Detect native Go support.
- Decide whether to coexist, disable duplicate features, or provide an explicit LSP mode.
- Modern Delve integration.
- Native project model integrations where LSP is insufficient.
- Go-specific inspections and refactorings.
- Performance profiling for large monorepos.

The parity phase is intentionally last. The old Go plugin demonstrates the breadth of native implementation required, but its code is not a safe compatibility base for current IntelliJ or Go.
