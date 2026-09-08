# Roadmap

## Phase 1: Core Editor

- Stabilize the IntelliJ LSP API integration.
- Validate diagnostics, completion, hover, navigation, references, rename, imports, symbols, and format-on-save.
- Extend context-action coverage beyond `gopls`'s existing code actions and the implemented struct-tag generator and completion, prioritizing actions that cannot be expressed through LSP.
- Extend the code vision where GoLand goes further: an implementations count on interfaces, and "Add method to interface".
- Add disk-backed integration tests.
- Offer to install `gopls` from the missing-server notification.

## Phase 2: Core Plus

- Go SDK and workspace discovery.
- `go.mod` and multi-module awareness. The test runner reads the module path out of the nearest
  `go.mod` to map an import path to a directory; a workspace with several modules is the case to
  check first.
- ~~Run configurations.~~ Done: a "Go Test" configuration and gutter arrows.
- ~~`go test`, benchmarks, and subtests.~~ Done: the platform test tree from `go test -json`, with
  subtests nested and rerun-failed. Benchmarks run and are reported, but their results are only
  console output; a benchmark's ns/op is not shown in the tree.
- ~~A `go run` configuration and a gutter action on `func main()`.~~ Done through `go run .`.
- A separate "Go Build" configuration.
- Debugging a test. The gutter offers Run only; Delve speaks DAP natively (`dlv dap`) and the
  platform has DAP support, but that API is still moving. See Phase 4.
- `go fmt`, `goimports`, `go vet` actions.
- Coverage integration, through `go test -coverprofile` and `CoverageEngine`.
- Improved status and project diagnostics.

## Phase 3: Managed Toolchains

- Download `gopls` by platform and architecture.
- Verify downloads with checksums.
- Select and install Go versions.
- Keep explicit user-configured executables authoritative.
- Provide rollback and update controls.

## Phase 4: GoLand Parity Attempt

- Detect native Go support.
- Revisit the plugin-owned code vision if a future TextMate release gives `.go` files a real PSI, which would make the platform's own usage and code author visions reusable.
- Decide whether to coexist, disable duplicate features, or provide an explicit LSP mode.
- Modern Delve integration, which is also what would put a Debug action beside the test runner's Run.
- Native project model integrations where LSP is insufficient.
- Go-specific inspections and refactorings.
- Performance profiling for large monorepos.

The parity phase is intentionally last. The old Go plugin demonstrates the breadth of native implementation required, but its code is not a safe compatibility base for current IntelliJ or Go.
