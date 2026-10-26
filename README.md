# Compose UI Wasm maintenance

- This standalone producer lives on the `kodex-ui-wasm` branch of
  [Stream29/compose-multiplatform-core](https://github.com/Stream29/compose-multiplatform-core).
  Product builds consume its immutable Maven binary, never this source build.
- It rebuilds only the Wasm UI leaf from the official `1.11.1` source archive.
  Upstream tag `v1.11.1` is commit `73ac84978a9e4ddca7e062dc0ee357ad875450fa`,
  tree `7985e1225865abd7591c8de83ba4a2dc7c5d39b8`.
  Archive, original listener, patch and patched listener hashes are checked.
- `semantics.patch` repairs browser semantics owner reattachment, disabled state,
  explicit control roles and checked state. The four regression methods retain
  the original failing assertions. `LICENSE.txt` is the original upstream license;
  the sources archive also retains the upstream license and this modification notice.
- Toolchain: Java 25, Gradle 9.6.1, Kotlin/Compose compiler 2.4.20,
  Compose plugin 1.11.1, source language 2.3 and Skiko 0.144.6.
- Versions are `1.11.1-kodex.<12-character producer commit>`. No official version
  is overwritten. Only `org.jetbrains.compose.ui:ui-wasm-js` is published;
  the root UI, other Compose modules and Native/JVM variants stay official.

## Build and verify

- Pass the exact clean source commit's version through `-PforkVersion`.
- Run `publishWasmJsPublicationToForkStagingRepository`. Gradle may write only
  this task-local candidate repository; remote and Maven Local publishing are blocked.
- Normalize and validate the leaf with `python3 publication.py prepare`.
  The self-contained metadata must not redirect to an unpublished fork root.
- Run the separate `binary-probe` build against the candidate repository.
  It resolves the official UI root plus the pinned leaf, executes all four strict
  browser regressions, checks the selected components and checks fresh Skiko extraction.
- Verify the product's optimized Wasm and browser acceptance before publication.
  The immutable publisher preflights every payload, rejects partial/existing
  versions and reads back every uploaded byte; it never overwrites or resumes.
