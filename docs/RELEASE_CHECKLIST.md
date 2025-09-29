# Debug Guardian Release Checklist

Use this checklist to make sure everything is ready before publishing a new Debug Guardian build.

## 1. Update version numbers
- [ ] Bump the project `version` in [`build.gradle`](../build.gradle).
- [ ] Update the `mod_version` replacement property in [`build.gradle`](../build.gradle) so the generated `mods.toml` matches.
- [ ] Adjust the publication `version` in the `publishing` block of [`build.gradle`](../build.gradle).
- [ ] If the release targets a new Minecraft or NeoForge version, update the corresponding values in the `neoForge` and `generateModMetadata` sections.

## 2. Review configuration defaults
- [ ] Confirm the defaults in [`DebugConfig`](../src/main/java/com/thunder/debugguardian/config/DebugConfig.java) reflect the intended behaviour for the release (e.g., tick thresholds and crash risk toggles).

## 3. Run automated checks
- [ ] `./gradlew build` to compile the mod, run unit tests, and generate resources.
- [ ] If gametests are defined, run `./gradlew runGameTestServer` to verify they pass.

## 4. Manual smoke test
- [ ] Launch the client run configuration (`./gradlew runClient`) and ensure monitors such as the `PerformanceMonitor` log expected warnings when thresholds are exceeded.
- [ ] If you changed crash monitoring logic, trigger representative scenarios to confirm `CrashRiskMonitor` alerts behave correctly.

## 5. Package & publish
- [ ] Check the contents of the generated JAR under `build/libs/` for the expected metadata.
- [ ] Update release notes with highlights of the changes.
- [ ] Publish to GitHub Packages or the desired distribution platform.

Checking these items helps catch configuration drift (like forgetting to update all version declarations) and ensures stability before sharing the update.
