# Agent Instructions

## Current Status

- This is an IntelliJ IDEA Community plugin scaffold, not a GoLand replacement yet.
- The implemented foundation is Go filename mapping with TextMate syntax highlighting, installed `gopls` discovery, LSP4IJ stdio startup, persistent `gopls` settings, restart action, native Go plugin conflict suppression for definition navigation, folding, signature help, and document symbols.
- Diagnostics, completion, hover, references, rename, code actions, run/test support, debugging, automatic `gopls` download, and Go-version selection are roadmap work, not verified current features. Format-on-save is implemented through local `gofmt`, with optional `goimports` import organization.
- Read `docs/FEATURES.md` and `docs/ROADMAP.md` before describing or extending feature coverage; update those files when status changes.

## Build

- The build targets IntelliJ IDEA Community `2024.2` / since-build `242`, uses LSP4IJ, compiles plugin bytecode for Java 17, and requires a Java 21 JDK to build.
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

- Keep IntelliJ integration in `src/main/kotlin/dev/go_lsp/intellij` and plugin registrations in `src/main/resources/META-INF/plugin.xml`.
- `GoLanguageServerFactory` owns LSP4IJ process creation; `GoLspDiscovery` owns executable lookup; `GoLspSettingsState` owns persisted application settings; do not duplicate these responsibilities.
- `gopls` owns Go parsing, type checking, diagnostics, completion, navigation, and refactoring. The local Go toolchain owns save formatting through `gofmt` and optional `goimports`; do not start a native Go PSI/type-system rewrite unless the roadmap explicitly calls for it.
- LSP4IJ is pinned in `gradle.properties`; upgrade it deliberately and rerun `verifyPlugin` because nightly API changes can break the integration.
- Do not add `com.intellij.modules.ultimate` or the official IntelliJ LSP dependency while IntelliJ IDEA Community remains a target.
- GoLand already ships native Go support. Do not claim GoLand compatibility or add overlapping integrations without first designing conflict handling.

## Documentation

- `README.md` is the user-facing setup and status summary.
- `docs/ARCHITECTURE.md` describes ownership boundaries and lifecycle.
- `docs/COMPATIBILITY.md` records supported platform/product constraints and risks.
- `docs/DEVELOPMENT.md` records setup, commands, and testing expectations.
- `docs/FEATURES.md` is the current feature matrix; distinguish implemented foundation, target capabilities, and not-yet-implemented work.
- `docs/ROADMAP.md` records future Core Plus, managed toolchain, and GoLand parity work.
