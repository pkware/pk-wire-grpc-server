Releasing
=========

This repository (`pkware/pk-wire-grpc-server`) publishes to Maven Central under the `com.pkware`
namespace (group `com.pkware.wiregrpcserver`).

### Prerequisites

Publishing is handled entirely by the `publish-snapshot` job in `.github/workflows/build.yml`,
which runs automatically on every push to `main`. The five secrets it needs —
`NEXUS_USERNAME`, `NEXUS_PASSWORD`, `SIGNING_KEY_ID`, `SIGNING_KEY`, `SIGNING_PASSWORD` — are
already provisioned as `pkware` org-level secrets available to this repository. No Sonatype
account, issue, or permission request is needed.

There is no separate release workflow and no tag-triggered publish. A release happens purely
because of what `VERSION_NAME` is set to on the commit at the HEAD of a push to `main`.

Cutting a Release
-----------------

1. Set `VERSION_NAME` in `gradle.properties` to the release version (no `-SNAPSHOT` suffix):

    ```
    export RELEASE_VERSION=X.Y.Z
    sed -i "" "s/VERSION_NAME=.*/VERSION_NAME=$RELEASE_VERSION/g" gradle.properties
    ```

2. Commit and push that change to `main` **as the sole commit of its own push**:

    ```
    git commit -am "Prepare for release $RELEASE_VERSION."
    git push
    ```

    This ordering matters: `build.yml` only looks at the version on the HEAD commit of the push
    it is reacting to. If this commit ends up bundled into a push whose HEAD is a different
    commit (for example, the next-SNAPSHOT commit from step 4), the release version is never
    seen and the promote step never fires.

3. `build.yml`'s "Publish to Maven Central" job now runs automatically: it builds, signs, and
   publishes the artifacts, detects the version is not a `-SNAPSHOT`, and promotes the staged
   artifacts on Central. No further action is required beyond watching that job go green in
   GitHub Actions.

4. **Immediately** after pushing the release commit — do not wait for the run from step 3 to
   finish — set `VERSION_NAME` to the next `-SNAPSHOT` version and push it as a second, separate
   push:

    ```
    export NEXT_VERSION=X.Y.Z-SNAPSHOT
    sed -i "" "s/VERSION_NAME=.*/VERSION_NAME=$NEXT_VERSION/g" gradle.properties
    git commit -am "Prepare next development version."
    git push
    ```

    Do this quickly: `build.yml` also runs on `workflow_dispatch` and reacts to any push to
    `main` (gated only on `github.ref == 'refs/heads/main'`). If `VERSION_NAME` is still the
    release version when some other push to `main` happens in the meantime — a merged Renovate
    PR, a manual dispatch — that run re-stages and re-promotes the same version, which Central
    rejects as a duplicate and reddens the run. The already-published release artifacts are
    unaffected; the cost is only a red build that needs no action.

5. Optionally, tag the release commit afterwards as a human-readable marker:

    ```
    git tag X.Y.Z <release-commit-sha>
    git push origin X.Y.Z
    ```

    This has no effect on CI: `build.yml` explicitly ignores tag pushes (`tags-ignore: '**'`). It
    exists purely for reference.
