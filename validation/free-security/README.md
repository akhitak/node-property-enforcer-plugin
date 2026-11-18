# Free plugin security checks — 18 November 2026

This workflow replaces the unavailable Xray service for this evaluation. It needs no paid account. Results are in [the summary](../2026-11-18-free-security.json); the exact input hashes are in [artifact-scope.json](artifact-scope.json). Public reports normalize local workspace and user-directory paths only. Original scanner output is retained locally under `target/free-security-20261118`.

## Scope

Create a fresh directory containing only the delivered JPI and the four provider JARs listed in the input manifest. Jackson is inspected inside the JPI. Do not point these tools at a Jenkins home, the Maven repository, a controller WAR, or the full Maven dependency SBOM, which can include provided Jenkins dependencies. Source analysis uses only this plugin's complete source package. npm packages are test tooling, assessed separately from runtime dependencies.

## Tools and settings

* [Syft 1.57.2](https://github.com/anchore/syft/releases/tag/v1.57.2): archive inventory and declared licenses.
* [Grype 0.122.0](https://github.com/anchore/grype/releases/tag/v0.122.0): known vulnerabilities. The supplied configuration requires a checked, current database; every severity is reviewed, including unknown severity, and there are no ignore rules or fix-availability filters. Enabling upstream kernel-header matching removes Grype's automatic header ignore rules; there are no kernel packages in this input.
* [OSV-Scanner 2.7.1](https://github.com/google/osv-scanner/releases/tag/v2.7.1): independent advisory lookup, license inventory and deprecation signals, with no overrides or reachability filtering.
* Existing SonarQube, SpotBugs and Gitleaks: source analysis and secrets. Microsoft Defender supplements these with a custom archive-only malware scan; it is included with the evaluated Windows installation, not required by the cross-platform plugin.

Release download hashes are recorded in [tool-downloads.json](tool-downloads.json). The scans were run with the following arguments; replace the executable names with their verified installation paths. `$runtimeDir` must be the isolated input directory described above, and `$reportDir` must be a separate output directory.

```powershell
syft scan "dir:$runtimeDir" --parallelism 2 -o "syft-json=$reportDir/runtime.syft.json" -o "cyclonedx-json=$reportDir/runtime.cdx.json"
grype "sbom:$reportDir/runtime.syft.json" --config validation/free-security/grype.yaml --fail-on negligible -o json --file "$reportDir/grype-runtime.json"
osv-scanner scan source --config validation/free-security/osv-scanner.toml --no-ignore --no-resolve --no-call-analysis=all --all-packages --all-vulns --licenses --x-flag-deprecated-packages --lockfile "$reportDir/runtime.cdx.json" --lockfile package-lock.json --format json --output-file "$reportDir/osv.json"
gitleaks dir "$pluginSourceDir" --redact --report-format json --report-path "$reportDir/gitleaks.json" --exit-code 1
```

Check every exit code. Require a nonempty inventory covering every input and compare OSV's runtime coordinates to that inventory. A missing, incomplete or failed report is not a pass. Grype's severity exit threshold does not substitute for checking every JSON finding, including unknown severity and suppressed matches. No finding, ignored match or unresolved runtime license was accepted in this run. A separate metadata-only Log4j 2.14.1 control verified that both scanners report CVE-2021-44228 and fail; the control is not a plugin dependency.

The online license service could not identify the six runtime coordinates. [The retained review](runtime-license-review.json) resolves those UNKNOWN results from the packaged declarations, allowing only MIT and Apache-2.0; it does not silently ignore them. The npm license inventory is in the OSV report and applies to test tooling only.

Run the existing `Scan-Sonar.ps1` after the documented Java/JavaScript verification, and run Maven `spotbugs:spotbugs spotbugs:check` against this plugin's compiled classes. Existing coverage was reused only after verifying production source against the tested build. The Windows supplement used `MpCmdRun.exe -Scan -ScanType 3 -File <isolated-runtime-directory> -DisableRemediation -CpuThrottling`; no scan of the laptop or Jenkins controller was performed.

The supplied files are scan configurations and evidence, not a newly installed continuous CI service. These tools do not duplicate every proprietary Xray feature or guarantee absence of unknown vulnerabilities.
