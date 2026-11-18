# Validation record — 18 November 2026

Status: **development candidate; unchanged strict SonarQube quality gate PASSED, coverage 100%.** This supersedes the earlier 68.4% report. Coverage measures executed code, not proof of defect-free software or complete OWASP compliance.

## Scope and reproducibility

Only Node Property Enforcer source, test tooling, dependencies and plugin endpoints were assessed. Jenkins 2.555.1 / Java 21.0.13 were unchanged disposable test hosts. No Jenkins core patch, controller-wide vulnerability scan or organizational Jenkins access was performed. Existing local Maven, JDK, SonarQube and Gitleaks installations were reused.

The final full run contains **70 reported Java tests: 67 passed, three intentional skips, zero failures/errors** (`final-release-verify.log`). It passes `verify -Penable-jacoco,security-audit`, including all coverage counters, SpotBugs, formatting and the fresh dependency scan. The skips are the opt-in manual browser fixture, the separately run endurance fixture, and one generated Jenkins harness check. The delivered JPI is this verified artifact.

Run `npm ci --ignore-scripts` followed by `npm run coverage` for the JavaScript suite. The final run contains **28 passing tests**, recorded in `final-release-js.log`. Test-only npm dependencies are not packaged in the JPI.

## Why coverage was 68.4%, and what changed

The earlier combined Sonar measure included browser scripts without an imported JavaScript coverage report and untested Java failure/lifecycle branches. Tests now execute those paths rather than suppressing or removing them. They exercise malformed inputs, failed writes, missing providers, permission boundaries, non-build executables, lifecycle cleanup, aborted/failed requests, bounded queues, nested restrictions and concurrent history updates.

| Measure | Final result |
| --- | --- |
| Sonar combined / line / branch coverage | 100.0% / 100.0% / 100.0% |
| Java lines / branches | 700/700 and 438/438 |
| Java instructions / methods / classes | 3,411/3,411; 207/207; 26/26 |
| JavaScript lines / branches | 377/377 and 340/340 |
| JavaScript statements / functions | 462/462 and 88/88 |
| Sonar bugs / vulnerabilities / code smells / hotspots | 0 / 0 / 0 / 0 |
| Sonar duplicated lines | 0.0% |
| SpotBugs | Zero findings and analysis errors |
| Job Restrictions 242.v6edda_c9e4ca_f | 16 restriction-content and queue tests passed against the final source |
| npm dependency audit | Current plugin test-tooling audit: zero reported vulnerabilities |
| Gitleaks source scan | Zero leaks in the complete staged source package |

No production code was excluded from reporting, no coverage counters were manufactured, and no rules or thresholds were relaxed. The inherited JaCoCo report exclusions were explicitly cleared. Instrumentation is limited to this plugin's package so it does not instrument Jenkins core. Reports inspect every compiled project class. All four production JavaScript files are instrumented; new source files are seeded with zero coverage before tests. JaCoCo requires 100% line, branch, instruction, method and class coverage; the JavaScript command requires 100% line, branch, statement and function coverage. Sonar imports both actual reports.

`target/final-security-20261118/rules-comparison.json` verifies that strict gate conditions and assigned profile keys, rule counts and rule-update timestamps match the snapshots taken before analysis. Existing narrowly documented production suppressions were not broadened. Historical resolved Sonar issues remain in the API export; the current scan has no open issues. The final unchanged gate passed (`final-release-sonar2.log`). A naming issue found in the new endurance test was corrected before this passing analysis.

The final source secret scan reported zero leaks. An additional scan of the generated evidence reported 119 generic-key detections: 61 Sonar issue IDs and 58 quality-profile/parent-profile IDs across the before/after snapshots. Each was verified against its structured API object as a noncredential identifier. The raw redacted findings and all classifications are retained; no scanner rule or source exclusion was added. The generated-report scan is not presented as a zero-detection result.

## Jenkins resource-health checks

The review identified and fixed practical resource risks:

* Restriction traversal has a whole-tree budget of 1,024 restriction/row visits, in addition to the existing depth 32 and list size 100 limits. Tests cover a shared graph representing over a billion naive traversal paths, a cycle, and oversized nested submissions.
* Each build stores at most 256 distinct node names, with immutable snapshots and deduplication. History writes for one build do not hold a lock shared by another build. Concurrent updates, slow storage and failed writes are tested. No plugin-owned thread pool or background worker is created.
* Browser validation runs at most four requests concurrently, cancels superseded requests, bounds pending work, uses weakly held input caches and has ten-second request timeouts. Page exit cancels pending work, clears timers and disconnects the observer. Browser history restoration resumes validation.
* Warning polling allows one request at a time, pauses on hidden pages and stops for detached UI or page exit. Timeout, failure and restoration paths are tested.

The final sample of **2,000 enforcement queue checks** measured **36.049 ms wall time, 31.250 ms thread CPU and 10,075,768 bytes of temporary allocation**. The test requires completion within ten seconds and allocation below 128 MiB. Concurrent-history tests completed without detected deadlocks; the tests also check the history cap and absence of newly created plugin threads.

The new read-only queue diagnostic bounds requests to eight IDs, matching nodes to 32 and name-rule traversal to 128 visits/depth 32/list size 100. Unknown restriction classes and subclasses are not evaluated. Tests cover these bounds, provider matching, anonymous access, job visibility, hidden pattern details, offline/eligible node fallback, browser request timeouts, widget replacement and navigation cleanup. These limits do not impose a runtime timeout on the provider's Java regular-expression matching.

These short regression checks supplement the isolated endurance results below. Neither is a production capacity benchmark or proof against every OOM, CPU spike, JVM failure or thread issue. Queue cost still depends on policy size, node count, request concurrency and external provider behavior. Arbitrary Java regular expressions are checked for syntax/completeness, not runtime complexity. A syntactically valid expensive expression can still burden the Job Restrictions provider when it evaluates jobs. Validate intended policies and soak-test representative workloads before production rollout.

## Completed endurance runs

Both opt-in JenkinsRule runs passed and their disposable controllers/agents exited cleanly. Each used Jenkins 2.555.1, Java 21.0.13, Matrix Auth 3.2.10, a 512 MiB controller heap, four online agents and an additional 60-second warm-up. Existing localhost:19085 nodes and settings were never used as load-test fixtures.

| Measurement | Job Restrictions 216.v0732cc7fb_076 | Job Restrictions 242.v6edda_c9e4ca_f |
| --- | --- | --- |
| Measured duration | 3,601.15 s | 1,801.33 s |
| Enforcement checks | 704,000 | 352,000 |
| Successful builds | 63 | 32 |
| Plugin HTTP requests | 17,726 | 8,864 |
| HTTP p99 | 5.42 ms | 5.52 ms |
| Post-GC heap: baseline → final | 90.90 → 55.59 MiB | 91.11 → 54.48 MiB |
| Maximum sampled post-GC heap | 90.99 MiB | 91.20 MiB |
| Live threads: baseline / maximum / final | 62 / 66 / 61 | 64 / 64 / 61 |
| Average controller CPU, fraction of one core | 7.36% | 7.04% |
| Longest recorded GC pause, including startup | 128.35 ms | 101.21 ms |

Every incomplete-node combination and the job-name mismatch stayed queued throughout. Direct, shared-label and compound-label builds used only the compliant agent. Running/completed status reported no false warning. The assertions detected no deadlocks, excessive retained-heap growth, thread growth beyond the unchanged limit, slow HTTP tail, failed request or failed build. These finite results support this tested workload; they do not prove absence of all leaks or JVM issues in production.

The full-depth JFR review found zero OOM, stack-overflow, InternalError or UnknownError events. It also retained **nine and seven handled events with production plugin frames**, respectively: JVM method-handle lookup exceptions and the parser's lookahead control-flow object. These were reviewed using [OpenJDK's handled lookup path](https://github.com/openjdk/jdk21u/blob/master/src/java.base/share/classes/java/lang/invoke/MemberName.java) and [Apache's parser explanation](https://issues.apache.org/jira/browse/JEXL-389), rather than being called absent or removed from the reports. No unreviewed plugin-associated error event remains. Full recorded stacks and classifications accompany the evidence.

The default JFR profile included unrelated process/environment/launch metadata. The test wrapper now disables those four metadata event types while retaining GC, memory, thread and error monitoring. A separate 60.83-second run passed 11,600 checks and two builds, recorded zero such metadata events and produced no JFR startup/Surefire channel warning. Relative JDK paths are resolved before Maven changes directories. The two longer recordings were scrubbed of the same metadata events before packaging; health events and classifications were retained. No coverage or quality rule was changed. The raw private recordings are not included in the distributed validation archive.

Machine-readable results are in `validation/2026-11-18-security-soak.json`. The evidence archive contains samples, scrubbed recordings, full test logs and recorder-scope checks.

## Functional behavior retained

Required Job Restrictions reject the built-in No restriction choice at every nested level. And/Or require both branches; Not requires its child; multi-entry lists need complete entries. User/group IDs and class rows cannot be blank. Expressions must be nonblank and valid Java syntax. Registered third-party restriction types retain their own validation; completeness checks do not prove policy effectiveness.

Required Matrix Auth needs explicit node permissions. Authenticated creators receive enabled node-scoped permissions on new/copied nodes only; existing edits do not infer or restore creator permissions. Automated tests cover local, email-style SSO and domain-qualified LDAP-style IDs through Jenkins APIs, without contacting external identity services.

Native Save/Apply/Enter checks keep incomplete forms open with errors beside the affected fields. Direct submissions have server-side enforcement. Existing agents cannot receive new queued work when build enforcement is enabled; running builds continue. Shared labels and compound label expressions are covered with two real online agents. The label fixture now saves label changes through Jenkins.updateNode before scheduling, so Jenkins' assigned-label cache represents the saved configuration.

## Earlier browser review

The earlier 17 November Chrome review used a disposable JenkinsRule preview with two online agents. Its first short-lived preview expired during review; that unfinished build is not counted as successful completion. A replacement preview extended only the manual fixture lifetime to 30 minutes to allow the requested 60-second inspection pauses. Production code and automated test thresholds were unchanged.

That earlier review confirmed field-local errors for missing properties and nested No restriction choices, same-page Save/Apply blocking, warning-only running/completed console messages, a shared-label build selecting the compliant agent, invalid-expression blocking, and a corrected expression persisting after Apply/reload. A pending build started when its assigned node was corrected, and saving the node while that build was running kept it online. The resumed build completed SUCCESS with no setup warning. These observations supplement the automated JavaScript/HtmlUnit checks; they are not a claim that every possible browser, node type or provider combination has been tested.

That preview review is historical. On 18 November, the updated JPI was installed through Jenkins' plugin upload endpoint and the user-authorized localhost:19085 controller was restarted with the same home, core WAR and startup arguments. All three requested existing agents reconnected. SHA-256 comparison confirms the installed JPI is identical to the delivered artifact. The pre-install and post-restart comparisons found all 29 existing node, job, user and controller configuration files unchanged. `queue.xml` is live queue state, not a configuration file, and changes when test builds are queued.

The earlier queue-message walkthrough and evidence were recorded under `target/queue-message-review/` and packaged in `dist/node-property-enforcer-validation.zip`. Queue explanations stay beside pending builds, identify the node/job/pattern, and link directly to that node's configuration. A blocked label pool lists separate reasons for its nodes. `test #3` finished SUCCESS on `Restricted`; `approved-npe-review-20261117-label-pool #2` and the compound-label job's build #2 finished SUCCESS on `restriction-validation-164439`. Running and completed consoles showed no false setup warning. Save on `noRestriction` stayed on the original form, focused its missing option, and displayed red field-local messages. The updated setting's help clearly explains checked and unchecked behavior.

Review also found that the dashboard's native hover text retained the incomplete reason. The final frontend correction keeps that plain-text tooltip consistent with the visible explanation, restores the original tooltip on fallback, and never enables HTML rendering. It was verified in Chrome after installing the final JPI; the JavaScript suite and unchanged Sonar gate were rerun successfully. The new queue explanation is a classic UI enhancement: Jenkins' native REST `why` is deliberately unchanged, and unsupported cases retain its native explanation. The previous initial wait for asynchronous queue widgets was corrected in the current review: new visible rows now request their first result promptly while the 30-second background refresh setting remains unchanged. Important views were held for at least 60 seconds.

That earlier configuration comparison showed **29 unchanged files**. Only that review's queued items 9, 10 and 11 were cancelled; completed build history remains available. `noRestriction`, `Restricted` and `restriction-validation-164439` are online, and Chrome was left on Nodes. No existing node, job or authorization settings were edited during the walkthrough.

## Earlier 18 November Chrome message audit

The earlier 18 November review used artifact SHA-256 `0fd2289f8782d02a67d53f57b7a5b2106db840f4303b074c024a09ff9feacd41`. Detailed cases and screenshots are in the validation archive's `earlier-chrome-review` folder. Important views were left visible for at least 60 seconds. Chrome interaction initially failed because another application covered its window; restoring Chrome visibility resolved that obstruction. The later rebuilt artifact and post-install checks are recorded below.

The review found and corrected three plugin issues before continuing: queue rendering could overwrite the Cancel tooltip, optional-provider linkage could prevent queue guidance from loading without Job Restrictions, and an oversized class name showed a syntax message instead of its 1024-character limit. Each correction was built, installed in the review environment and retested. The final full verification and unchanged Sonar gate passed after those corrections.

The recorded checks include:

* Both missing properties, security alone missing, and restriction alone missing: dashboard, job-history and node messages name exactly the incomplete settings for the relevant node. Valid properties and unrelated nodes are not added to those explanations.
* Same-page Save/Apply/Enter blocking, empty permission matrices, every built-in restriction choice, nested unrestricted children, empty lists, blank user/group/class rows, invalid expression syntax and length boundaries. Corrected input clears its field-local error. The class-limit correction was specifically retested after deployment.
* No optional providers installed: the plugin and queue component load. Selected unavailable requirements have local red provider/version guidance. Empty rows, available core selections and disabled requirements have no unrelated error.
* All behavior help links; independent warning and existing-edit switches; strict enforcement restored after each optional-mode exercise. With enforcement on, three new builds remain queued for one or both missing requirements. Hiding warnings does not permit those builds to run.
* New-node creation adds the authenticated creator's five Agent permissions, blocks default No restriction, and retains the permissions after a valid save and reload. Existing-node edits do not automatically grant permissions.
* A deliberately warning-only preview build continued when enforcement was enabled during execution and finished SUCCESS. Its running and completed console explanations changed appropriately, listed only its actual node, and left the log/result unchanged. A later build stayed queued until both required properties were completed; saving the complete node released it, and it finished SUCCESS. Current warnings on the earlier completed console then cleared.

All setting changes above were confined to disposable preview controllers. The existing localhost:19085 node, job, user and controller configurations were compared against the 29-file baseline. A finite scenario checklist and 100% measured code coverage do not establish exhaustive UI coverage across every browser, provider, node type or Jenkins version.

The final deployed-controller reruns produced these results without editing their configurations:

| Review job | Build | Result / selected node |
| --- | --- | --- |
| `test` | 4 | SUCCESS on `Restricted` |
| `approved-npe-review-20261117-label-pool` | 3 | SUCCESS on `restriction-validation-164439` |
| `approved-npe-review-20261117-label-compound` | 3 | SUCCESS on `restriction-validation-164439` |
| `approved-npe-review-20261117-direct` | 2 | SUCCESS on `restriction-validation-164439` |
| `npe-review-20261117-noRestriction` | Original queue item 15 | Still waiting; both required properties missing |
| Restricted-name and blocked-label review jobs | Queue items 17 and 16 | Stayed queued with the correct node-specific explanations; cancelled only these newly created review entries after validation |

The running and completed direct/shared-label consoles had no false warning about unused nodes. All three requested nodes remained online. The final comparison again found zero changes among the 29 existing configuration files; the original queued build was retained. Source, installed JPI and test-stage production resources were compared, and the installed and delivered JPI hashes match.

## Post-install verification of the refreshed scan build

The refreshed build was installed on localhost:19085 with SHA-256 `1d8b47b3f6a52aa4bd4adfb731488f11c76eb5b4bcc319e6cf87ca1a03cdb024`. A ZIP-entry comparison found all 58 runtime classes/resources unchanged from the previously reviewed JPI; only the private-build manifest timestamp differed. The new source changes repair the NVD scan configuration and add the opt-in endurance test and documentation.

The visible Chrome walkthrough confirmed both missing options beside their fields, Save remaining on the same page, empty permission and default No restriction blocking, and separate local errors for both unrestricted And children when Apply was used. All attempted form changes were discarded without saving. `test #5` completed SUCCESS on `Restricted`; shared-label build `approved-npe-review-20261117-label-pool #4` completed SUCCESS on `restriction-validation-164439`. Their running and completed consoles contained no warning about unused nodes. The original queue item 15 remained pending and its explanation named both missing options only for `noRestriction`. Important views were held for at least 60 seconds; screenshots and timestamps are under `final-security-20261118/chrome` in the evidence archive.

An additional attempt to view the single-missing-option combinations initially stopped at sign-in when browser control could no longer verify its active URL safely. That interrupted attempt is not counted as completed UI evidence. A later authorized retry recovered Chrome using a fresh review tab and normal sign-in; no browser security setting or safety check was changed. The in-app browser fallback was not needed.

The resumed visible review confirmed all queue combinations in a fresh disposable controller: both missing options appear together, a node with security configured shows only the missing restriction, and a node with restrictions configured shows only missing security. The two single-option job pages showed the same precise explanations. A complete node had no setup warning; a job-name mismatch identified its node, job and required pattern. The deployed controller's Configure node link also opened the correct form, and Save retained that original form with both red field-local errors. The principal message views and final Save view were held for at least 60 seconds. Screenshots and inspection timestamps are retained under `chrome-recovery-20261118` in the evidence archive. No production code changed during this recovery.

The accompanying disposable run passed its unchanged assertions over 481.34 measured seconds: 94,000 enforcement checks, nine successful builds and 2,368 plugin HTTP requests. The incomplete and mismatched jobs never started; direct/shared/compound-label builds used the compliant node. The test controller and its agents exited normally. The final original-controller comparison found all 29 configuration files unchanged, all three requested agents online and original queue item 15 still pending. Chrome was left on the original Nodes page. Results are summarized in `validation/2026-11-18-chrome-recovery.json`.

All three requested existing nodes stayed online. The final 29-file configuration comparison is included with the reports. The test builds add build history, but do not change existing node, job, user or controller configuration.

## Current dependency scans and remaining service prerequisites

The earlier NVD update failure was Dependency-Check 13.0.0's [empty API-key defect](https://github.com/dependency-check/DependencyCheck/issues/8715). The security profile now uses the [official NVD JSON 2.0 feeds](https://nvd.nist.gov/vuln/data-feeds), without modifying scanner binaries or lowering failure thresholds. A new database was populated on 18 November. Fresh scans passed for both Job Restrictions 216.v0732cc7fb_076 and 242.v6edda_c9e4ca_f with Matrix Auth 3.2.10: **zero unsuppressed vulnerabilities**. The final rebuilt artifact's dependency scan also passed. Nine runtime dependency/source entries were assessed; Jenkins/core and test dependencies were not scanned.

The existing expiring suppression for matrix-auth's incorrect matrix-project CVE mapping remains unchanged: CVE-2024-23900 is the sole suppressed finding. Scanner errors and any other CVSS finding, including zero, still fail the scan. npm audit separately reported zero vulnerabilities in this plugin's test tooling. The previous cached 10 November result is not used as fresh evidence.

The signed JFrog CLI 2.133.0 was installed after no local CLI or connection profile was found. An artifact-only Xray attempt failed because no JFrog/Xray service URL or credentials are configured. **Xray has not passed.** Its raw failure and artifact hash are retained under `target/final-security-20261118/xray`. The optional Sonatype OSS Index analyzer also lacks service credentials; this is not claimed as a completed scan. No new broad DAST result is claimed; direct plugin HTTP tests cover permissions, POST and CSRF boundaries.

This is an evaluation build, not an approved Jenkins community release or a guarantee of future LTS/weekly compatibility. Jenkins hosting metadata/maintainer setup remains a community-release prerequisite. The user selected the completed free scanning workflow below instead of activating Xray; Xray is no longer a required service for this evaluation. Isolated endurance results do not replace testing at an installation's actual scale with its other plugins and workloads. The package includes source, test reports, coverage reports, scanner evidence, resource measurements and SHA-256 checksums.

## Completed free alternative to Xray

On 18 November, the unchanged delivered JPI and four optional-provider JARs were inventoried with Syft 1.57.2 and scanned with Grype 0.122.0 and OSV-Scanner 2.7.1. Official release downloads were SHA-256 verified. The inventory contains six unique component/version pairs: this plugin, bundled Jackson Core 2.22.4, Matrix Auth 3.2.10, Ionicons API 88.va_4187cb_eddf1, and Job Restrictions 216.v0732cc7fb_076 and 242.v6edda_c9e4ca_f. Syft represents this plugin's outer JPI and inner JAR separately, producing seven records. No Jenkins core or unrelated artifact was included.

Grype reported zero vulnerabilities and zero suppressed findings across all seven records. Its database was built on 18 November; checksum verification, a mandatory update check and a maximum age of 24 hours were enforced. The lowest severity fails, issues without fixes are included, and the effective ignore/exclude lists are empty. The report was also checked for findings with unknown severity. OSV checked the same six distinct runtime versions and all 95 npm test-tooling packages, with all vulnerabilities visible, no overrides and no reachability filtering: zero known vulnerabilities were reported. Both scanners detected Log4Shell in a separate metadata-only negative control and returned their expected nonzero failure codes. No vulnerable executable or dependency was downloaded for that control.

OSV's online license lookup returned UNKNOWN for all six runtime coordinates. Those results were retained and reviewed against Syft's packaged license declarations: MIT (including the exact declaration `MIT License`) and Apache-2.0, with no unresolved declaration. All 95 npm tooling licenses were returned and inventoried separately; those packages are not shipped in the JPI. This records declared licenses, not a legal certification.

Fresh SonarQube analysis passed the existing strict gate with zero bugs, vulnerabilities and code smells, and 100% measured coverage using the unchanged verified coverage reports. Fresh SpotBugs analysis inspected 26 plugin classes with zero bugs or analysis errors. Gitleaks scanned the complete staged plugin source with zero leaks. Microsoft Defender's custom archive scan found no threats in the five input files; remediation was disabled, archive inspection was enabled, and file exclusions were ignored for that scan. The signature timestamp is recorded in the evidence. No controller scan or settings change was performed.

The reports and strict configurations are in [the scan evidence](validation/free-security/README.md), with a [machine-readable summary](validation/2026-11-18-free-security.json). Production source and JPI bytes did not change, so no redeployment or repeated UI walkthrough was necessary. Existing node, job, user and controller configurations were compared again.

These free tools complete the selected checks without a JFrog service. They do not reproduce JFrog's proprietary malicious-package intelligence, contextual analysis, repository download controls or operational-risk scoring. OSV's deprecation check and the malware scan provide narrower signals. No new DAST or infrastructure scan is claimed; the plugin has no deployed infrastructure to assess, and its existing permission/POST/CSRF tests remain the evidence for its HTTP boundaries. No scanner result establishes perfect security or future compatibility.




## Matrix Authorization 3.3 compatibility follow-up

Community dependency preparation exposed a regression: Matrix Authorization 3.3 renders expandable permission cards instead of the older permission table. The browser form guard now supports both layouts, adds the authenticated creator safely, and places empty-permission guidance immediately after the active matrix control. Existing-node edits do not gain automatic permissions.

The evaluation artifact remains on Jenkins 2.555.1 with its original dependency minimums. Its new SHA-256 is `86690b2903b5ebd2e7d09bef1b1fea4313e434894a0ab71ba826499015eff417`. Full Maven verification passed with 70 reported tests, zero failures/errors and three pre-existing skips. All 26 production classes have zero missed JaCoCo instructions, branches, lines, methods and classes. All 30 JavaScript tests passed with 100% line, branch, function and statement coverage. The unchanged strict SonarQube gate passed, with zero open issues; SpotBugs reported zero bugs.

Targeted Matrix 3.3 tests passed after fixing the regression: seven property-content tests and four form-guidance tests. Chrome verified same-page Save/Apply blocking for empty permission cards, creator grants, default No restriction blocking, valid expression correction, successful creation and persistence of all five creator node permissions. The original 3.2.10 provider was then retested after deploying the packaged fix: both missing fields, empty permissions, No restriction, same-page Save/Apply and creator auto-fill worked. No test form was saved on the existing controller. Principal corrected, blocked and persisted views were held for at least 60 seconds; the initial brief card-error view was also captured.

Fresh Grype and OSV scans assessed the packaged JPI and optional provider sets, including Matrix 3.3 and its additional dependencies. Grype catalogued 14 entries and found zero matches or ignored matches, using the 18 November database with mandatory freshness checks. OSV assessed 10 distinct runtime packages and 95 npm test packages, with zero vulnerabilities. Gitleaks found no secrets in the explicit public-source snapshot. This run did not rescan Jenkins core. Previous NVD and endurance results remain historical evidence; this change modifies one production JavaScript resource, with production Java and evaluation dependency versions unchanged.

The deployed plugin is active on localhost:19085. All 29 existing node, job, user and controller configuration files remain unchanged, and the three requested agents are online. Detailed results are recorded in `validation/2026-11-18-matrix-cards.json`; local raw reports and screenshots are under `target/matrix-cards-20261118`. The separate community dependency/baseline proposal has not been deployed or released.
