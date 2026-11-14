# Contributing to HyperCameraMod

Thank you for your interest in contributing! This document outlines the process for contributing to this project.

## Ways to Contribute

- **Bug reports** - Use the [bug report template](.github/ISSUE_TEMPLATE/bug_report.yml)
- **Feature requests** - Use the [feature request template](.github/ISSUE_TEMPLATE/feature_request.yml)
- **Code changes** - Submit a pull request
- **Documentation** - Improve README, comments, or wiki
- **Testing** - Test on different devices/camera versions and report results

## Development Setup

### Prerequisites

- JDK 21
- Android SDK (compileSdk 37, build-tools 37.0.0)
- Android NDK r26c (for any native code)
- A rooted Xiaomi device with HyperOS 3/4 and Xiaomi Camera 6.7 for testing
- LSPosed or Vector with libxposed API 101/102

### Building

```bash
# Set SDK location in local.properties (not committed)
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# Build release APK
./gradlew assembleRelease
```

The unsigned release APK will be at `build/outputs/apk/release/xiaomi-camera-mod-release.apk`.

### Testing

1. Install the APK on your test device
2. Enable the module in LSPosed/Vector with scope `com.android.camera`
3. Open HyperCameraMod settings and configure toggles
4. Tap **Force Stop Camera** (requires root) and open Camera again
5. Verify the features work as expected

## Pull Request Process

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/your-feature-name`
3. Make your changes with clear, focused commits
4. Ensure the build passes: `./gradlew assembleRelease`
5. Test on a device if possible
6. Submit a pull request against `main`

### Commit Message Format

Use conventional commits:

```
type(scope): description

[optional body]

[optional footer]
```

Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `build`, `ci`

Examples:
- `feat(hooks): add Original portrait toggle for 18 Pro Max`
- `fix(blur): cap RenderEffect blur radius at 100`
- `docs(readme): update feature availability table`

### Code Style

- Follow the existing code style in the project
- Kotlin: use `ktlint` / Android Kotlin style guide
- Java: follow Google Java Style (4-space indent, no tabs)
- Add comments for non-obvious logic, especially hooks

## Adding Support for New Camera Versions

When a new camera version breaks a feature:

1. Check the logs for `does not match` lines - they name the missing class/field
2. Add a new profile in the relevant hook (e.g., `LEGEND_PROFILES`, `STYLE_PROFILES`, filter profiles)
3. Use the dex dump (`classes.dex`, `classes4.dex`) to find the new obfuscated names
4. Test thoroughly before submitting

## Reporting Issues

When reporting a bug or requesting a feature:

- Include device model, HyperOS version, Camera version
- Include relevant logcat output (filter `HyperCameraMod`)
- Steps to reproduce
- Expected vs actual behavior

## Code of Conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). By participating, you agree to uphold this code.

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0 (see [LICENSE](LICENSE)).