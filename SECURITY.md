# Security Policy

## Supported Versions

Only the latest stable release and the current `main` branch receive security updates.

| Version | Supported |
| ------- | --------- |
| 1.0.x   | ✅        |
| < 1.0   | ❌        |

## Reporting a Vulnerability

**Do not report security vulnerabilities through public GitHub issues.**

Instead, email the maintainer directly at: **security@rezikham.example** (replace with actual contact)

Include:

- Description of the vulnerability
- Steps to reproduce
- Potential impact
- Any suggested fix

You will receive a response within 7 days. If the vulnerability is confirmed, a fix will be developed and released as soon as possible.

## Security Considerations

This module:

- **Hooks into the Xiaomi Camera process** at runtime via LSPosed/Vector
- **Requires root** only for the "Force Stop Camera" button (uses `su -c "am force-stop"`)
- **Does not modify** any system partition or APK on disk
- **Does not request** INTERNET, storage, or sensitive permissions
- **Uses libxposed API 101/102** for the settings-to-camera bridge (remote preferences)
- **Stores settings** in LSPosed's remote preferences (local to the module scope)

### Threat Model

| Threat | Mitigation |
|--------|------------|
| Malicious module impersonation | Module package is `io.rezikham.hypercameramod`; verify in LSPosed/Vector |
| Settings tampering | Remote preferences are scoped to the module; only the module and settings app can read/write |
| Camera instability | Module is defensive: hooks log and skip when classes/fields don't match; force-stop restores stock |
| Root escalation | Only `am force-stop` uses root; no shell injection (uses ProcessBuilder with explicit args) |

### Known Limitations

- The module hooks internal Xiaomi Camera classes that may change between versions
- On unsupported camera versions, features are silently skipped (logged, not crashed)
- The "Hide launcher icon" option uses an activity-alias; settings remain accessible via LSPosed/Vector module list

## Disclosure Timeline

1. **Day 0** - Vulnerability reported privately
2. **Day 1-3** - Acknowledgment and initial assessment
3. **Day 7** - Status update (confirmed/rejected/need more info)
4. **Day 30** - Target for fix release (may vary by complexity)
5. **Release** - Fixed version published; CVE requested if applicable

## Hall of Fame

Security researchers who have responsibly disclosed vulnerabilities:

- (none yet)