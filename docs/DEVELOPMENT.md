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

`runIde` launches a development IntelliJ IDEA (Ultimate) sandbox with the plugin. The first run downloads the IntelliJ IDEA distribution, which is large. No license is needed for the LSP API in the sandbox.

Enable `#com.intellij.platform.lsp` in `Help | Diagnostic Tools | Debug Log Settings` inside the sandbox to log LSP traffic.

## Testing Strategy

`GoLspSemanticTokensTest` covers the semantic token colour mapping as a plain JUnit 5 test; every case in it is a token/modifier pair `gopls` actually emits. `GoLspMethodStubsTest` covers the Go text "Implement interface" writes, and the parsing of the `(*Repo).Get` names `gopls` gives methods. Unit tests should likewise cover executable discovery, settings persistence, command construction, and argument handling without launching an IDE.

Keep logic that can be tested this way out of the LSP and code vision plumbing - `GoStructTags` and `GoLspMethodStubs` exist precisely so the text handling is reachable without an IDE.

Functional tests should use a disk-backed IntelliJ fixture. LSP server implementations may require real filesystem paths and file watchers; in-memory light fixtures are not sufficient for all lifecycle cases.

Each LSP test should:

1. Create a temporary Go module.
2. Start the server through `LspServerManager`.
3. Wait asynchronously for initialization.
4. Assert one or more LSP results.
5. Stop the server during teardown.

### Checking Editor-Only Behaviour

Some things cannot be seen from a test or a log - whether an inlay actually renders, what the PSI a
grammar produces really looks like. The technique that worked for the code vision was a throwaway
`ProjectActivity`, gated on an environment variable, that opens a Go file in the sandbox and logs
what the providers would emit:

```sh
GO_LSP_PROBE=1 ./gradlew runIde --args="/path/to/a/go/project"
```

with the output read from `.intellijPlatform/sandbox/.../log/idea.log`. That is how the single-leaf
TextMate PSI was found, after two rounds of reasoning from the platform bytecode had reached the
wrong conclusion. Remove the probe before committing.

## Release Checks

Before publishing:

- Run `test`.
- Build the plugin ZIP.
- Run Plugin Verifier against every declared IntelliJ build.
- Test missing, configured, and discovered `gopls` paths.
- Test at least one module project and one project without `go.mod`.
- Open a Go file in a Git repository and confirm the usage count, the committer, and "Implement
  interface" all appear, and that generating a method adds the imports its signature needs.
- Test macOS ARM64 and Linux x64 process startup.
