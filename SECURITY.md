# Security Policy

We take the security of the Software seriously and appreciate responsible reports of potential security vulnerabilities.

This document describes how to report security issues and how security-related reports are generally handled.

## Reporting a Security Vulnerability

Please do **not** report suspected security vulnerabilities through public issue trackers, public discussions, community forums, or other public channels.

Instead, report security vulnerabilities privately to:

[SECURITY EMAIL]

If available, you may also use:

[PRIVATE SECURITY REPORTING URL]

Please include as much relevant information as possible.

Useful information may include:

* affected Software version;
* deployment environment;
* affected component;
* vulnerability type;
* reproduction steps;
* proof of concept;
* expected security impact;
* known mitigations;
* relevant logs or screenshots; and
* whether you believe the issue is currently being exploited.

Please avoid including sensitive customer data, credentials, secrets, or personal data unless strictly necessary.

## What Qualifies as a Security Vulnerability

Security reports may include, for example:

* authentication bypasses;
* authorization vulnerabilities;
* privilege escalation;
* remote code execution;
* injection vulnerabilities;
* insecure direct object references;
* cross-tenant data access;
* unintended data exposure;
* cryptographic weaknesses;
* secret or credential exposure;
* sandbox escapes;
* vulnerabilities allowing unauthorized administrative access;
* vulnerabilities affecting isolation between users, projects, tenants, or workloads; or
* other issues that could materially compromise confidentiality, integrity, or availability.

General software bugs without a meaningful security impact should normally be reported through the project's standard issue tracker.

## Acknowledgment

We may acknowledge receipt of a security report after it has been received and reviewed.

Unless separately agreed in writing, no specific acknowledgment or response time is guaranteed.

Commercial support agreements may define separate response-time commitments.

## Investigation

We may investigate reported vulnerabilities based on factors including:

* severity;
* exploitability;
* affected versions;
* practical impact;
* availability of mitigations;
* likelihood of exploitation;
* affected deployment models; and
* resources required to investigate and remediate the issue.

We may request additional information from the reporter.

Submitting a security report does not create an obligation to provide a fix.

## Severity Assessment

We may classify vulnerabilities according to their potential impact.

Where appropriate, we may use commonly accepted severity frameworks such as CVSS as one input into our assessment.

Final severity classification remains at our discretion unless otherwise agreed in writing.

## Remediation

If we determine that a reported vulnerability requires remediation, we may address it through one or more of the following:

* a patch release;
* a minor release;
* a major release;
* a configuration change;
* updated documentation;
* a mitigation or workaround;
* a dependency update;
* an architectural change; or
* another appropriate measure.

A security fix may require upgrading to a newer Software version.

We do not guarantee that security fixes will be backported to older releases.

## Supported Versions

Unless otherwise announced, only the latest generally available release is considered actively maintained for security purposes.

Older versions may not receive security fixes.

Users are encouraged to keep deployments reasonably up to date.

Any separate commercial support agreement may define additional supported versions or maintenance commitments.

## Public Disclosure

Please allow a reasonable opportunity for investigation and remediation before publicly disclosing a vulnerability.

We ask security researchers to coordinate disclosure with us where practical.

We may publish:

* security advisories;
* vulnerability descriptions;
* affected-version information;
* remediation guidance;
* mitigations;
* patched-version information; or
* CVE information where appropriate.

The timing and content of any public disclosure may depend on the nature and severity of the issue.

## Responsible Disclosure

We ask reporters to:

* act in good faith;
* avoid accessing data that does not belong to them;
* avoid modifying or deleting third-party data;
* avoid disrupting production systems;
* avoid denial-of-service testing without prior authorization;
* avoid social engineering;
* avoid phishing;
* avoid physical security testing;
* avoid unnecessary persistence in affected systems; and
* stop testing if it risks causing harm to users or systems.

## Security Research Authorization

This policy does not grant permission to access systems, accounts, infrastructure, or data that you do not own or have explicit authorization to test.

Testing should normally be limited to environments that you own or are authorized to assess.

If the Licensor operates an official security research or bug bounty program, the terms of that program will govern testing performed against systems covered by that program.

## No Bug Bounty by Default

Unless explicitly announced otherwise, reporting a vulnerability does not create any entitlement to:

* payment;
* a bounty;
* compensation;
* reimbursement;
* credit;
* merchandise; or
* any other reward.

We may choose to recognize contributors at our discretion.

## Confidential Information

Information exchanged during vulnerability coordination may be security-sensitive.

We ask reporters not to publish sensitive technical details before coordinated disclosure where doing so could materially increase risk to users.

Any legally binding confidentiality obligation must be agreed separately in writing.

## Security of Modified Versions

Users may modify the Software where permitted by the applicable license.

We are not responsible for vulnerabilities introduced by:

* user modifications;
* forks;
* custom builds;
* unsupported integrations;
* third-party plugins;
* deployment configuration;
* infrastructure configuration; or
* third-party dependencies outside our control.

A reported issue affecting only a modified or unsupported deployment may not be treated as a vulnerability in the generally available Software.

## Third-Party Dependencies

The Software may rely on third-party libraries, frameworks, databases, services, operating systems, runtimes, or other components.

Security issues affecting third-party components may need to be reported to the relevant upstream maintainer.

Where appropriate, we may update dependencies or publish mitigations.

## Security Commitments

This Security Policy does not create any contractual commitment to:

* provide security fixes;
* provide fixes within a specific period;
* support specific versions;
* issue CVEs;
* provide private patches;
* provide security monitoring;
* provide incident response services; or
* notify individual users of every vulnerability.

Any such commitment must be expressly agreed in a separate written agreement.

## Commercial Customers

Commercial licensing alone does not create additional security-response obligations unless explicitly stated in an applicable Order Form or Support Agreement.

Commercial customers may purchase or receive separate support commitments where agreed in writing.

## Security Updates

Security-related releases may be made available through the same generally available release channels used for other Software releases.

There is no separate commercial-only security release stream unless expressly stated otherwise.

The public availability of a security fix does not change the licensing requirements applicable to Commercial Production Use.

## Contact

Security reports:

sk@svenkubiak.de