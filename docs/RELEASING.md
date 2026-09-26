# Releasing Sipher

Pushing a version tag builds Sipher, runs the tests on all six platforms, and publishes the jar with its changelog to
GitHub Releases, CurseForge and Modrinth (the `release` job in `.github/workflows/build.yml`). Nothing is uploaded if
any build or test fails.

## Each release

1. Set `mod_version` in `gradle.properties`, for example `0.2.0`.
2. Write the changelog in `docs/changelogs/<version>.md`. It is used as-is on all three sites, so write it for
   players. The first line is the title.
3. Check [docs/COMPATIBILITY.md](COMPATIBILITY.md): no protocol version change, no changed payload codecs, nothing
   changed on the `models-v1` release.
4. Merge to `main` and wait for the build to pass.
5. Tag that commit and push the tag:

   ```bash
   git tag v0.2.0
   git push origin v0.2.0
   ```

The tag must match `mod_version` and the changelog must exist, or the job stops before uploading anything.

The release type follows the version: `0.x` and `-beta` versions are **beta**, `-alpha` versions are **alpha**, and
everything else from `1.0.0` is a **release**. Beta and alpha versions are marked as pre-releases on GitHub.

## One-time setup

Until both the project id and the token of a site are set, the workflow skips that site and publishes to GitHub only.

| Site | Repository variable | Repository secret | Where to get the token |
|---|---|---|---|
| CurseForge | `CURSEFORGE_PROJECT_ID` (the number in *About Project*) | `CURSEFORGE_TOKEN` | CurseForge account settings → API tokens |
| Modrinth | `MODRINTH_PROJECT_ID` (from the project page's ⋯ menu → *Copy ID*) | `MODRINTH_TOKEN` | Modrinth settings → Personal access tokens, with the *Create versions* scope |

Set them in the repository settings under *Secrets and variables → Actions*, or with the GitHub CLI:

```bash
gh variable set CURSEFORGE_PROJECT_ID --body 123456
```

```bash
gh secret set CURSEFORGE_TOKEN
```

Create both projects by hand first. The CurseForge project needs the description from `docs/curseforge/`, and the
Modrinth project needs the *AI functionality* content disclosure ticked (on-device speech recognition and translation)
and its environment set to required on the client, optional on the server.

## Development builds

Every merge to `main` also replaces the rolling [Development build](https://github.com/Eiriksb/Sipher/releases/tag/dev)
pre-release. It is for testing and is never uploaded to CurseForge or Modrinth.
