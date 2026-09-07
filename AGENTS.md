# Agent Instructions

## Current Status

- This is an IntelliJ IDEA plugin scaffold, not a GoLand replacement yet.
- The implemented foundation is Go filename mapping with TextMate syntax highlighting, installed `gopls` discovery with a missing-server notification, startup through the IntelliJ LSP API over stdio, persistent `gopls` settings, a status bar widget, a restart action, and native Go plugin conflict suppression.
- Editor features (diagnostics, completion, hover, navigation, references, code actions, formatting, folding, inlay hints) come from the platform LSP client and `gopls`; which ones exist depends on the IDE version. Run/test support, debugging, automatic `gopls` download, and Go-version selection are roadmap work. Format-on-save is implemented through local `gofmt`, with optional `goimports` import organization.
- Read `docs/FEATURES.md` and `docs/ROADMAP.md` before describing or extending feature coverage; update those files when status changes.

## Build

- The build targets the IntelliJ IDEA (Ultimate) distribution `2025.2.6` / since-build `252.25557`, uses the IntelliJ LSP API, compiles plugin bytecode for Java 21, and requires a Java 21 JDK to build.
- The plugin is compiled against IU because the LSP API only ships in that distribution. Since 2025.2.1 the API works without a paid license, and since 2025.3 there is only one IntelliJ IDEA distribution.
- Set `JAVA_HOME` to Java 21 before running Gradle. The repository wrapper delegates to `GRADLE_HOME` or a system `gradle`; the configured Gradle version is `9.7.1`.
- `gopls` is an external runtime prerequisite. The plugin currently does not download it; use an installed executable or configure its full path in `Settings | Tools | Go LSP`.
- Useful commands:
  - `./gradlew test` runs the test task; there are currently no repository tests, so it may report `NO-SOURCE`.
  - `./gradlew buildPlugin` builds `build/distributions/go-lsp-intellij-<version>.zip`.
  - `./gradlew runIde` launches a development IntelliJ sandbox.
  - `./gradlew verifyPluginStructure` checks the plugin descriptor and archive structure quickly.
  - `./gradlew verifyPlugin` runs Plugin Verifier against supported IDEA builds and can download several large IDE distributions; run it for compatibility changes, not every edit.
- The normal verification order is `test`, `buildPlugin`, `verifyPluginStructure`, then `verifyPlugin` when platform/API compatibility needs checking.

## Architecture

- Keep IntelliJ integration in `src/main/kotlin/com/github/pocikode/go_lsp_intellij` and plugin registrations in `src/main/resources/META-INF/plugin.xml`.
- Everything that references `com.intellij.platform.lsp` must be registered in `META-INF/go-lsp.xml`, which is loaded through the optional `com.intellij.modules.lsp` dependency. `plugin.xml` must stay loadable without the LSP module.
- `GoLspServerSupportProvider` decides when to start a server; `GoLspServerDescriptor` owns the `gopls` command line and file mapping; `GoLspSupport` owns the Go-file and native-plugin checks; `GoLspDiscovery` owns executable lookup; `GoLspSettingsState` owns persisted application settings; `GoLspRequests` owns synchronous `gopls` requests made outside the platform client; do not duplicate these responsibilities.
- Navigation is plugin-owned: `GoLspReferenceProvider` (references, hover styling), `GoLspDeclarationProvider` (declarations), `GoLspSymbol` (navigatable symbol + search target), `GoLspUsageSearcher` (usages). `GoLspServerDescriptor` disables the platform's go-to-definition support so the built-in reference provider cannot duplicate targets; do not re-enable it without removing `GoLspReferenceProvider`. Keep other editor features on the platform client where it offers them.
- `gopls` owns Go parsing, type checking, diagnostics, completion, navigation, and refactoring. The local Go toolchain owns save formatting through `gofmt` and optional `goimports`; do not start a native Go PSI/type-system rewrite unless the roadmap explicitly calls for it.
- Use the 2025.2 API names (`LspServerSupportProvider`, `LspServerDescriptor`, `LspServerManager`). IntelliJ 2026.1.4 renamed them to `LspIntegrationProvider`, `LspClientDescriptor`, and `LspClientManager`; the old names still work there but are deprecated. Switch only when the since-build moves to 261.
- Do not add a dependency on `com.intellij.modules.ultimate`; the LSP module is the only IU-specific dependency and it must remain optional.
- GoLand already ships native Go support. Do not claim GoLand compatibility or add overlapping integrations without first designing conflict handling.

## Documentation

- `README.md` is the user-facing setup and status summary.
- `docs/ARCHITECTURE.md` describes ownership boundaries and lifecycle.
- `docs/COMPATIBILITY.md` records supported platform/product constraints and risks.
- `docs/DEVELOPMENT.md` records setup, commands, and testing expectations.
- `docs/FEATURES.md` is the current feature matrix; distinguish implemented foundation, target capabilities, and not-yet-implemented work.
- `docs/ROADMAP.md` records future Core Plus, managed toolchain, and GoLand parity work.
