# Contributing

Discuss substantial behavior changes in an issue before implementing them. Include the Jenkins, Java and optional provider versions when reporting a problem. Remove credentials, user identities and private job or node details from examples.

Run the complete plugin checks from the repository root:

```shell
mvn -B -ntp verify -Penable-jacoco
npm ci --ignore-scripts
npm run coverage
```

Keep all existing coverage thresholds and security checks. Add focused regression tests for behavior changes, including server-side enforcement and the browser guidance when applicable. Use a disposable Jenkins test instance; never use a production controller as a test fixture.

Use public Jenkins extension points. Preserve optional dependency loading, permission and CSRF checks, bounded work, and existing-node settings. Document any change in the compatibility baseline or enforcement boundary.

Report suspected security vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

Contributors are expected to follow the [Jenkins Code of Conduct](https://www.jenkins.io/project/conduct/).
