# Development

## Local Setup

Install Java 21 and ensure `JAVA_HOME` points to it. The IDE runtime is not necessarily the correct build JDK.

Verify Go tooling:

```sh
go version
gopls version
```

Set `gopls` explicitly in the plugin settings when testing a non-default installation.

## Build Tasks

```sh
./gradlew test
./gradlew buildPlugin
./gradlew runIde
./gradlew verifyPlugin
```

`runIde` launches a development IntelliJ IDEA sandbox with the plugin and its LSP4IJ dependency.

## Testing Strategy

Unit tests should cover executable discovery, settings persistence, command construction, and argument handling without launching an IDE.

Functional tests should use a disk-backed IntelliJ fixture. LSP server implementations may require real filesystem paths and file watchers; in-memory light fixtures are not sufficient for all lifecycle cases.

Each LSP test should:

1. Create a temporary Go module.
2. Start the server.
3. Wait asynchronously for initialization.
4. Assert one or more LSP results.
5. Stop the server during teardown.

## Release Checks

Before publishing:

- Run `test`.
- Build the plugin ZIP.
- Run Plugin Verifier against every declared IntelliJ build.
- Test missing, configured, and discovered `gopls` paths.
- Test at least one module project and one project without `go.mod`.
- Test macOS ARM64 and Linux x64 process startup.
