# Security policy

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability. Use GitHub's private security-advisory flow for this repository and include reproduction steps, affected versions, impact, and any safe remediation ideas.

Do not include production credentials, raw Play Integrity tokens, private keys, lease secrets, publisher tokens, advertising identifiers, or personal data in a report.

## Supported version

Until formal releases begin, only the latest commit on `main` is supported.

## Security boundaries

Apex Sentinel is a policy and audit service. It does not independently establish Google Play device trust and does not replace the Apex production trust activation runbook. Its default development credentials and memory adapter are forbidden by production startup validation.
