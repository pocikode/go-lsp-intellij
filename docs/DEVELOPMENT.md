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
./gradlew verifyPlugin -PverifyIdes=all
```

`verifyPlugin` verifies against the platform the build already resolved - `ides { current() }`, which
resolves to the same extracted IDE `intellijIdeaUltimate(platformVersion)` produced. It downloads
nothing and takes about half a minute, so it is cheap enough to run on any change.

`-PverifyIdes=all` switches to the release selector, one IDE per release from `pluginSinceBuild` to
`262.*`. That is the check that can catch a break in a newer build - the LSP API renames in 2026.1.4,
say - and it downloads several gigabytes, one IDE at a time. Run it before a release. Those IDEs sit
in `~/.gradle/caches/<gradle version>/transforms` and can be deleted afterwards; only the directory
holding the `platformVersion` build is needed for everyday work.

The task exits non-zero on a pre-existing internal API usage, `ShowUsagesAction.showUsages`, which
the code vision needs and the platform offers no public equivalent for. Read the verdict line
("Compatible. N usages of ...") and look for problems naming the classes the change touched; the
exit code alone does not distinguish a new break from that standing one.

`runIde` launches a development IntelliJ IDEA (Ultimate) sandbox with the plugin. The first run downloads the IntelliJ IDEA distribution, which is large. No license is needed for the LSP API in the sandbox.

Enable `#com.intellij.platform.lsp` in `Help | Diagnostic Tools | Debug Log Settings` inside the sandbox to log LSP traffic.

## Testing Strategy

`GoLspSemanticTokensTest` covers the semantic token colour mapping as a plain JUnit 5 test; every case in it is a token/modifier pair `gopls` actually emits. `GoLspMethodStubsTest` covers the Go text "Implement interface" writes, and the parsing of the `(*Repo).Get` names `gopls` gives methods. `GoTestFunctionsTest` covers finding test declarations and building `-run` patterns, and `GoTestEventTranslatorTest` covers the whole `go test -json` translation by feeding it event lines and asserting the service messages that come out - including the escaping, which is why it goes through `ServiceMessageBuilder` rather than string concatenation. Unit tests should likewise cover executable discovery, settings persistence, command construction, and argument handling without launching an IDE.

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
- Open a `*_test.go` file and confirm the gutter arrows appear, that one runs its test, that
  subtests nest under their parent, that a failure links to its line, and that rerun-failed
  re-runs only what failed.
- Run the tests of a package with no `go.mod` above it, and of a package that fails to compile, and
  confirm the console shows the toolchain's error rather than an empty tree.
- Test macOS ARM64 and Linux x64 process startup.
