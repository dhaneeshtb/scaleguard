# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | ✅ Active support  |

## Reporting a Vulnerability

We take security seriously at ScaleGuard. If you discover a security vulnerability, please report it responsibly.

### How to report

1. **DO NOT** open a public GitHub issue for security vulnerabilities
2. Email: **dhaneeshtnair@gmail.com** with the subject line `[SECURITY] ScaleGuard Vulnerability Report`
3. Include:
   - Description of the vulnerability
   - Steps to reproduce
   - Potential impact
   - Suggested fix (if any)

### What to expect

- **Acknowledgement**: Within 48 hours of your report
- **Assessment**: We'll evaluate severity within 1 week
- **Fix timeline**: Critical issues within 7 days, others within 30 days
- **Disclosure**: We'll coordinate responsible disclosure with you
- **Credit**: We'll credit you in the release notes (unless you prefer anonymity)

### Security best practices for ScaleGuard users

- Always set `SCALEGUARD_ADMIN_USER` and `SCALEGUARD_ADMIN_PASSWORD` via environment variables
- Never expose the `/config` endpoint to the public internet without authentication
- Keep your ScaleGuard instance updated to the latest version
- Use HTTPS with auto-provisioned certificates (`autoProcure: true`)
- Enable rate limiting to prevent abuse
- Regularly rotate admin credentials

## Known security considerations

- The `/health`, `/healthz`, `/metrics`, and `/stats` endpoints are unauthenticated by design (standard for monitoring). Ensure your firewall rules are appropriate if these shouldn't be public.
- The built-in DNS server binds to port 53 — only enable this if you need DNS functionality.
