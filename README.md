# Node Property Enforcer

Require selected node settings before users save an agent configuration or start new builds on that agent. Administrators choose the required properties and whether existing agents should show warnings or wait for configuration before accepting more builds. Running builds continue normally.
See [pom.xml](pom.xml) for the minimum Jenkins version and the [Jenkins Java support policy](https://www.jenkins.io/doc/book/platform-information/support-policy-java/) for the corresponding Java requirements. No Jenkins patches, bytecode agents, or core replacements.

## Supported node properties

| Option | Provider | Property |
|---|---|---|
| Enable node-based security | Matrix Authorization Strategy (`matrix-auth`) | `AuthorizationMatrixNodeProperty` |
| Restrict jobs execution at node | Job Restrictions (`job-restrictions`) | `JobRestrictionProperty` |

Both are plugin features. Environment variables, tool locations and disk-space thresholds are examples of core node properties. The installed descriptors populate the selector automatically; no organization-specific names, accounts, permissions or regexes are embedded.

Sources: [Matrix Auth source](https://github.com/jenkinsci/matrix-auth-plugin/blob/matrix-auth-3.2.10/src/main/java/org/jenkinsci/plugins/matrixauth/AuthorizationMatrixNodeProperty.java), [Job Restrictions](https://plugins.jenkins.io/job-restrictions/).

## Installation

1. Matrix Authorization Strategy and Job Restrictions are **optional dependencies**. This plugin installs and loads with neither, either or both present. Their options remain listed and are marked unavailable when needed. Selecting an unavailable option while “Require this option” is checked shows a red explanation directly below that selection. Other selections remain clear; there is no separate availability banner.
   Matrix Authorization also needs **Project-based Matrix Authorization Strategy** under Manage Jenkins → Security → Authorization. Installing the plugin or using global matrix authorization alone does not expose its node checkbox. Its requirement cannot be enabled until this setup is active. A previously saved requirement remains visible and enforced until its setup is corrected or the requirement is turned off. This plugin never changes Jenkins authorization settings.
2. Upload `target/node-property-enforcer.jpi` through **Manage Jenkins → Plugins → Advanced settings** and restart Jenkins.
3. Open **Manage Jenkins → System → Required node properties**.
4. Add required properties and open the question-mark help beside each setting before saving.

Empty rows are ignored when saving. An empty list, or disabling every individual rule, leaves enforcement inactive. A saved policy survives restart. A corrupt/unreadable policy fails closed until repaired. Restart is required for installation, disabling and removal.

Install or enable a missing provider in Jenkins' plugin manager and restart; its option can then be required, without automatically enforcing it. Do not downgrade newer installed providers to match a minimum. An installed provider must meet its declared minimum. If a previously enforced provider disappears, its saved rule continues to fail compliance until the rule is disabled/removed or the provider is restored. This prevents silent loss of enforcement.

## Administrator controls

| Setting | Enabled | Disabled |
|---|---|---|
| Require this option (per row) | Require that node-property option | Retain the saved row without enforcing it |
| Allow new builds only on nodes with all required options enabled | Nodes with incomplete required settings wait before starting new builds; another suitable node may run the job | Nodes may start builds with incomplete required settings; banner settings still apply |
| Check required options before saving existing nodes | Required settings must be complete before Save/Apply; XML updates require the web form | Existing-node edits and XML updates remain permitted |
| Show warnings on node pages | Show missing options and a configuration link for users allowed to edit the node | Hide the banner; build and Save/Apply checks still apply |
| Show messages for queued builds and on build and console pages | Explain supported queue restrictions and display the current status of the build's agents | Hide this plugin's queue/build/console messages; other enforcement is unchanged |
| Check warning status every (seconds) | 15–300 seconds; default 30 | Controls only warning updates; hidden tabs do not poll |

Behavior switches default to enabled, but there are initially no requirements. For an introductory rollout, leave banners enabled and turn off both build pausing and mandatory existing-node edits. After teams update their nodes, enable build pausing and mandatory edits.

**New-agent creation and copying always enforce enabled requirements in the standard web forms.** XML creation is blocked while any requirement is enabled; use the web form. There is no administrator bypass for form checks, but administrators can change the policy.

On native node creation and edit forms, red Jenkins-style messages appear below missing required checkboxes, the empty permission matrix and an unrestricted job selector. Correcting a field clears its message. Using **Save**, **Apply** or **Enter** with incomplete settings keeps the form and entered values visible and focuses the first field needing attention. An unavailable option is explained inside Node Properties. Existing-node validation follows the edit-check setting. Server checks also cover direct submissions, disabled JavaScript and policy changes after the page opened; the fallback page explains what needs attention.

## Existing nodes and running builds

The plugin never periodically disconnects agents, cancels builds or changes build results. A noncompliant agent can remain connected while receiving no new queue work. Running builds finish normally. After compliance is restored, Jenkins' next scheduling cycle can assign waiting work again. A build can instead run on another eligible agent if its labels permit.

**Shared labels are evaluated per agent.** If several agents share a job's label, an agent with the required properties can accept the build while another agent missing them waits. If no matching agent meets the requirements, the build stays queued. Jenkins still applies compound label expressions such as `pool && linux && !maintenance`. Requirements are global across agents, with the built-in controller exempt; labels do not create separate policies or exemptions.

Classic job history and dashboard queues list all incomplete required properties for each relevant node and explain supported job-name restrictions beside the waiting build. For example: **Node “Restricted”: Job “example” does not match the required job-name pattern “test”.** Users who can configure that node also get a **Configure node** link; other readers see a general explanation without the pattern. Dashboard hover text uses the same explanation, with HTML rendering disabled. The original tooltip returns when the plugin has no explanation, and the Cancel button keeps its own tooltip. Names are rendered as text, and the check uses the provider's actual matching behavior, including the job's full name rather than its display name.

These explanations appear only when every matching node is online and has an identified reason preventing this job from running. A pool with an eligible node, offline nodes, unknown restriction types or no assigned label keeps Jenkins' native explanation. Only built-in name restrictions and their logical combinations are inspected. Requests cover at most eight visible queue items and 32 matching nodes per item, with bounded tree traversal. Complex user-provided Java regular expressions can still be expensive; these bounds are not a regex execution timeout. Unknown/custom restriction implementations are never invoked by this diagnostic endpoint.

The queue display is read-only: it does not change scheduling, node settings, console logs or Jenkins' native REST `why` value. The Job Restrictions dependency returns an empty native reason in the tested case, so this plugin provides the explanation in the classic UI without patching that dependency. Required-property explanations also work without that optional provider installed. The dashboard enhancement requires a visible cancel-item link to identify its queue row; job history uses Jenkins' queue-entry ID. Newly loaded queue rows show “Checking this build's node requirements…” while requesting their first result promptly; they do not wait for the normal refresh interval. Failed status requests restore Jenkins' native text. Browser navigation cancels polling and disconnects the observer.

The banner is placed above the classic node/build page content. On a running console it explains that the current build continues. On a completed console it explains that the historical result is unchanged. It polls a read-only, permission-checked endpoint and changes only its own DOM element; it does not reload the page or alter console text. Restoring compliance clears it at the next successful poll. If polling fails, an existing warning remains visible.

Freestyle builds use Jenkins' persisted built-on node. Other executor-based builds can record agent names through the public `SubTask.getOwnerExecutable()` contract. History stores at most 256 distinct node names per build; this bounds banner history and does not limit enforcement on any node. Historical completed Pipeline builds from before installation have no plugin agent history. Pipeline/custom executor behavior and alternate consoles such as Blue Ocean require separate compatibility validation; the classic Freestyle console is the browser-validated case.

## Exact enforcement boundary

* Standard permanent-agent create, copy and edit forms, including `/manage/computer/` aliases, are checked before Jenkins persists the submitted configuration.
* Requests are bounded to 1 MiB of submitted JSON and 64 nesting levels. Duplicate JSON keys, duplicate form parameters and conflicting property type declarations are rejected. Jenkins retains its native authorization and CSRF checks.
* CLI, Configuration as Code, cloud provisioning and direct `Jenkins.addNode` calls are **not universally vetoed before registration**. With build pausing enabled, the queue dispatcher blocks new work on any noncompliant agent regardless of its creation route. This is scheduling containment, not a core admission hook.
* The built-in controller is exempt. Existing in-flight scheduling decisions are not revoked. Processes outside the Jenkins queue and controller-level administrators are outside the boundary.
* `NodePropertyPolicy.get().violation(node)` is a public pre-registration check available to provisioning integrations; `null` means compliant.

## Required values and their limits

Required node-based security must contain at least one explicit node permission for a user or group. On creation, the authenticated creator receives all enabled node-scoped permissions, using Jenkins' user ID independently of the login provider. Anonymous and SYSTEM actions do not receive automatic grants. Editing an existing node never adds or restores creator permissions. The grant does not include global permissions or change inheritance.

Required Job Restrictions are checked through the entire built-in restriction tree:

| Choice | Required configuration |
| --- | --- |
| No restriction (take any) | Cannot be used at any level of a required restriction. |
| And / Or | Complete both First and Second. |
| Not | Complete its nested restriction. |
| And / Or (multiple entries) | Add at least one entry and complete every entry. |
| Regular Expression (Job Name) | Enter a nonblank expression with valid Java syntax, up to 4096 characters. |
| Job class | Add at least one full class name; every row must be complete and at most 1024 characters. |
| Started By User | Add at least one user ID; every row must be complete. The anonymous/upstream switches do not replace this list. |
| Started By member of group | Add at least one group ID; every row must be complete. |

Empty rows must be filled or removed. Nesting is limited to 32 levels and lists to 100 entries. A whole-tree budget of 1024 restriction/row visits also bounds processing; larger trees must be simplified. Errors appear beside incomplete fields; Save and Apply keep the form open. Java expression and class-name checks finish before saving. The same content checks apply to direct form submissions and queue eligibility for existing nodes. They validate completeness, not whether expressions, logical combinations or ACLs implement your intended access policy. Registered restriction types from other plugins retain their own content validation. Other node properties are checked for presence only.

The browser allows at most four concurrent field-validation requests, cancels superseded requests, and stops pending work when leaving the page. Requests time out after ten seconds. Warning polling allows one request at a time, pauses while the page is hidden, and resumes when browser history restores the page. The plugin creates no background thread or executor pool. Build-history disk writes do not hold a lock shared by other builds. These limits reduce resource risks; they do not guarantee zero JVM, memory, thread or performance problems in every installation.

Matrix node permissions also require **Project-based Matrix Authorization Strategy**. Unavailable or inapplicable properties fail the check. Select properties supported by all agents covered by your policy.

## Dependency policy

| Optional integration / library | Declared minimum | Reason |
|---|---|---|
| Matrix Authorization Strategy | 3.3 | Selected by the Jenkins plugin BOM required for this community candidate |
| Job Restrictions | 242.v6edda_c9e4ca_f | Selected by the Jenkins plugin BOM for the community release; the separate evaluation build supports 216.v0732cc7fb_076 |
| Ionicons API | 94.vcc3065403257 | Transitive dependency selected by the community BOM |
| Jackson API | 2.22.4-449.v4ee9f4ec4123 | Jenkins' shared Jackson provider; this plugin uses Core for bounded strict JSON parsing |

These are **supported minimums**, not a claim that every older historical release is incompatible. The oldest version that merely loads is not necessarily a safe or maintainable choice. Matrix Auth releases before 3.2.10 have a [published deserialization issue](https://www.jenkins.io/security/advisory/2026-04-29/). Job Restrictions has much older releases, but they are not certified by this project. A newer installed dependency satisfies Jenkins' minimum-version manifest.

## Build and validation

Use JDK 21+ and Maven with the Jenkins plugin parent, plus Node.js 22.12+ for browser-script tests:

```shell
mvn -B -ntp verify -Penable-jacoco
npm ci --ignore-scripts
npm run coverage
```

The package phase produces `.hpi` and the byte-identical `target/node-property-enforcer.jpi`, plus SHA-256. Jenkins accepts both extensions. `verify` runs tests, access-modifier checks, SpotBugs, formatting and a CycloneDX inventory. The JaCoCo profile requires 100% Java line, branch, instruction, method and class coverage. The JavaScript coverage command instruments every production `.js` file and requires 100% lines, branches, statements and functions. Sonar imports both reports; its strict gate is unchanged. Test-only npm packages are not shipped inside the plugin.

`BrowserFixtureTest` is opt-in (`-Dtest=BrowserFixtureTest -Dpolicy.browserFixture=true`); it runs a disposable JenkinsRule instance with two local standard-input/output agents and automatically expires after 12 minutes. Both agents share `ui-shared-pool`; `policy-label-demo` prints its selected agent, while `policy-console-demo` remains assigned to the first agent. Never install its fixture initialization files on a real controller.

Optional plugin-scope dependency scan:

```shell
mvn -Psecurity-audit verify
```

The profile downloads the official NVD JSON 2.0 feeds, avoiding Dependency-Check 13.0.0's empty API-key defect. It fails on scanner errors or any unsuppressed CVSS finding, including score zero. Provided Jenkins/core and test dependencies are outside this plugin-only scan. The one narrowly scoped, expiring suppression corrects Dependency-Check's mapping of `matrix-auth:3.3` to the **different** `matrix-project` product (CVE-2024-23900), with a primary advisory link in the suppression file. Scan evidence and limitations are recorded in `VALIDATION.md`.

For an isolated hour-long endurance check on Windows:

```powershell
./scripts/Test-Soak.ps1 -Minutes 60 -JavaHome $env:JAVA_HOME
```

This opt-in test creates a temporary controller and four local agents. It repeatedly checks each missing-option combination, queue explanations, shared/compound labels, and running/completed build status. It never connects to an existing Jenkins home. The controller is limited to a 512 MiB heap; samples and a Java Flight Recorder file are written under `target/soak`. The recording omits machine-wide process lists, environment variables, system properties and JVM launch arguments. After warm-up, the test requires less than 32 MiB retained-heap growth, at most 16 additional live threads, HTTP p99 below two seconds, no deadlocks, and average controller CPU below half of one core. All incomplete nodes must stay blocked and all completed builds must use the compliant node. These are measured test limits, not a guarantee for every workload or third-party restriction.

The completed free alternative uses Syft, Grype, OSV-Scanner, SonarQube, SpotBugs and Gitleaks, with a scoped Microsoft Defender archive check. See [the results](validation/2026-11-18-free-security.json) and [repeatable scan commands](validation/free-security/README.md). No JFrog account is required for this workflow.

An optional Xray binary scan can target only the resulting JPI:

```shell
jf scan target/node-property-enforcer.jpi --server-id=YOUR_EXISTING_PROFILE --watches=YOUR_STRICT_WATCH --format=sarif --vuln=true --fail=true --fixable-only=false --recursive=false --threads=1
```

It requires an existing JFrog Platform connection with Xray access and a watch whose policies fail the scan for every reported severity. The fail flag alone does not create that policy. An absent connection, unsuccessful scan, incomplete component analysis, or report containing findings is not a pass. No JFrog account, entitlement or credentials are bundled with this project.

## Community publication and ongoing support

Hosting is requested in [jenkins-infra/repository-permissions-updater#5464](https://github.com/jenkins-infra/repository-permissions-updater/issues/5464). This repository contains only `node-property-enforcer`; the separate evaluation artifacts and other plugins in `akhitak/JenkinsPlugins` are outside the hosting request. Candidate verification is recorded under [validation](validation). Hosting/security review and release permissions are still required before a community release.

Keep the baseline stable, test selected later LTS and weekly releases, and retest on Java and dependency upgrades. Supported public extension points reduce maintenance risk; they cannot guarantee compatibility with every future release or zero vulnerabilities for years.
