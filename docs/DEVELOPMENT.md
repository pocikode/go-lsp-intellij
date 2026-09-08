# Development

## Local Setup

Install IntelliJ IDEA in `/Applications/IntelliJ IDEA.app`. The build uses that installation as both
its IntelliJ Platform dependency and its Java 25 toolchain, so it does not download an IDE into the
Gradle cache. Override the location with `-PplatformPath=/path/to/IntelliJ IDEA.app`.

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

`verifyPlugin` verifies against the local platform dependency through `ides { current() }`. Do not
add an IDE release selector for routine or release checks: selectors download complete IDE builds.
Upgrade the installed IntelliJ IDEA and rerun the build when checking a newer platform.

The task exits non-zero on a pre-existing internal API usage, `ShowUsagesAction.showUsages`, which
the code vision needs and the platform offers no public equivalent for. Read the verdict line
("Compatible. N usages of ...") and look for problems naming the classes the change touched; the
exit code alone does not distinguish a new break from that standing one.

`runIde` launches the installed IntelliJ IDEA in a development sandbox with the plugin. It reuses the
local application and does not download another IDE distribution.

Enable `#com.intellij.platform.lsp` in `Help | Diagnostic Tools | Debug Log Settings` inside the sandbox to log LSP traffic.

## Testing Strategy

`GoLspSemanticTokensTest` covers the semantic token colour mapping as a plain JUnit 5 test; every case in it is a token/modifier pair `gopls` actually emits. `GoLspMethodStubsTest` covers the Go text "Implement interface" writes, and the parsing of the `(*Repo).Get` names `gopls` gives methods. `GoMainFunctionTest` covers recognition of the runnable entry point. `GoTestFunctionsTest` covers finding test declarations and statically named table cases plus building `-run` patterns, and `GoTestEventTranslatorTest` covers the whole `go test -json` translation by feeding it event lines and asserting the service messages that come out - including the escaping, which is why it goes through `ServiceMessageBuilder` rather than string concatenation. Unit tests should likewise cover executable discovery, settings persistence, command construction, and argument handling without launching an IDE.

Keep logic that can be tested this way out of the LSP, code vision and test-runner plumbing - `GoStructTags`, `GoLspMethodStubs`, `GoTestFunctions` and `GoTestEventTranslator` exist precisely so the interesting part is reachable without an IDE. `GoTestEventTranslator` takes its output as a lambda for that reason; the platform wiring around it is a dozen lines in `GoTestEventsConverter`.

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

The same technique verified the test runner: the probe ran `GoTestRunner.run` against a throwaway
Go module, waited, then walked `SMTRunnerConsoleView.resultsViewer.testsRootNode` logging each
node's name, magnitude, leaf-ness and resolved `Location`. That is the only way to see that
subtests really nest, that a skip really reads as ignored, and that every node navigates to the
right line - none of which the unit tests can reach, since they stop at the service messages.

## Release Checks

Before publishing:

- Run `test`.
- Build the plugin ZIP.
- Run Plugin Verifier against every declared IntelliJ build.
- Test missing, configured, and discovered `gopls` paths.
- Test at least one module project and one project without `go.mod`.
- Open a Go file in a Git repository and confirm the usage count, the committer, and "Implement
  interface" all appear, and that generating a method adds the imports its signature needs.
- Open a `package main` file and confirm the gutter arrow on `func main()` runs the whole package,
  including source from another file, and passes configured program arguments.
- Open a `*_test.go` file and confirm the test and table-case gutter actions appear, each runs the
  selected name, successful actions retain a green passed icon, failed actions turn red, subtests
  nest under their parent, passed and failed test logs are present, benchmark results are printed,
  failures link to their lines, and rerun-failed re-runs only what failed.
- Run the tests of a package with no `go.mod` above it, and of a package that fails to compile, and
  confirm the console shows the toolchain's error rather than an empty tree.
- Test macOS ARM64 and Linux x64 process startup.
