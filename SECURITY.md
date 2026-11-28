# Security Policy

## Supported versions

BitChord ships often, so only the latest release gets security fixes. If you're on an older build, update from the [Releases](https://github.com/kushagrasinghx/BitChord/releases) page before reporting.

| Version | Supported |
|---|---|
| Latest release | Yes |
| Older releases | No |
| Beta and dev builds | Best effort |

## Reporting a vulnerability

**Please don't open a public issue, discussion or pull request for a security problem.**

Report it privately through GitHub: go to the repository's **Security** tab and click **Report a vulnerability**. Only the maintainers can see the report.

Please include:

- what the problem is and what an attacker could do with it
- the affected part (Android app, TV, Wear OS, web app, browser extension or Listen Together server) and version
- steps to reproduce, or a proof of concept
- anything you know about a fix

BitChord is maintained by volunteers. We aim to acknowledge a report within 7 days and to tell you whether we accept it within 14. Accepted issues are fixed in the next release where possible, and we'll agree a disclosure date with you. We credit reporters in the release notes unless you'd rather stay anonymous.

## Scope

In scope:

- The BitChord apps in this repository: Android (phone, TV, Wear OS), the web app and the browser extension
- The Listen Together server in `backend/`
- How BitChord stores and sends account data: Discord, Google and Drive sign-ins, SMB credentials, backups and party codes
- The build and release pipeline in `.github/workflows`
- Official release artifacts published on this repository's Releases page

Out of scope:

- Vulnerabilities in third-party services BitChord talks to (YouTube, Deezer, Discord, Google and others). Report those to the service.
- Builds from forks, mirrors or app stores this project doesn't publish
- Attacks that need a rooted or jailbroken device, or physical access to an unlocked one
- Self-hosted copies of the Listen Together server that were changed or misconfigured
- Outdated dependencies without a working exploit against BitChord
- Denial of service by flooding the public server with traffic

## Safe harbor

We won't pursue or support legal action against anyone who researches in good faith under this policy: stay within scope, don't access or change other users' data, don't degrade the public Listen Together server, and give us reasonable time to fix the problem before you disclose it.
