# unetd-android

## Versions: always the latest stable release

Use the latest **stable** release of every tool, library, plugin, SDK level and CI
action — never pin an older one for convenience. When touching the build, check for
newer releases and upgrade. Pre-releases (alpha, beta, RC, milestone) are not
"latest".

Look versions up from a primary source rather than from memory:

- Gradle: `https://services.gradle.org/versions/current`
- Android Gradle Plugin, compatibility, max API: `https://developer.android.com/build/releases/gradle-plugin`
- AndroidX: `https://developer.android.com/jetpack/androidx/releases/<library>`
- Compose BOM: `https://developer.android.com/develop/ui/compose/bom/bom-mapping`
- Kotlin: tags of `JetBrains/kotlin`
- JDK: `https://api.adoptium.net/v3/info/available_releases`, checked against
  Gradle's Java compatibility matrix
- GitHub Actions: the action's tags and its `action.yml` at that tag
- NDK: `https://developer.android.com/ndk/downloads` (the LTS line), CMake,
  platform and emulator system-image packages: `sdkmanager --list` (the names
  carry a minor version now, e.g. `platforms;android-37.0`)
- androidx.test: `https://developer.android.com/jetpack/androidx/releases/test`
- Go: `https://go.dev/dl/?mode=json`
- json-c: its latest release tag; wireguard-go: upstream HEAD (`git ls-remote`),
  pinned as a pseudo-version in `native/libwg-go/go.mod`

Things that move together, so bump them together:

- AGP sets a minimum Gradle version.
- The Compose compiler plugin must equal the Kotlin version; Kotlin is pinned via
  `kotlin-gradle-plugin` on the root buildscript classpath because AGP 9 compiles
  Kotlin itself.
- compileSdk/targetSdk cannot exceed AGP's maximum supported API level.
- The JDK must be one Gradle lists as supported for running builds.

Upstream `third_party/unetd` and `third_party/libubox` track upstream HEAD,
`third_party/json-c` its latest release tag; after bumping either, check that the
patch series in `patches/` still applies (`scripts/apply-patches.sh`). The
submodule gitlinks are committed at the *pristine* upstream commit, never at a
patched one. wireguard-go is not a submodule: `native/libwg-go/go.mod` requires it
at a pseudo-version of upstream HEAD (its tags are not Go semver); bump with
`go get golang.zx2c4.com/wireguard@<commit> && go mod tidy` in that directory and
re-pin the `golang.org/x/*` modules to their latest releases afterwards.
