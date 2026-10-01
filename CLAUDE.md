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
- NDK: `https://developer.android.com/ndk/downloads` (the LTS line), CMake and
  platform packages: `sdkmanager --list`
- Go: `https://go.dev/dl/?mode=json`
- json-c, wireguard-go: upstream tags / HEAD (`git ls-remote`)

Things that move together, so bump them together:

- AGP sets a minimum Gradle version.
- The Compose compiler plugin must equal the Kotlin version; Kotlin is pinned via
  `kotlin-gradle-plugin` on the root buildscript classpath because AGP 9 compiles
  Kotlin itself.
- compileSdk/targetSdk cannot exceed AGP's maximum supported API level.
- The JDK must be one Gradle lists as supported for running builds.

Upstream `third_party/unetd`, `third_party/libubox` and `third_party/wireguard-go`
track upstream HEAD, `third_party/json-c` its latest release tag; after bumping any
of them, check that the patch series in `patches/` still applies
(`scripts/apply-patches.sh`) and that `native/libwg-go/go.mod` still resolves
(`go mod tidy` there). The submodule gitlinks are committed at the *pristine*
upstream commit, never at a patched one.
