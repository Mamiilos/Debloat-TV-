# Maintaining the MaInfo fork

Personal fork: https://github.com/Deni7/free-my-tv

Original project: https://github.com/simbasjungles/free-my-tv

The maintained integration branch is **`codex/mainfo-dashboard`**. `origin` points
to the personal fork and `upstream` to the original project. Keep the original
project's history: merge upstream updates into the integration branch rather
than copying files or replacing the fork with a new checkout.

## Update the integration

With a clean working directory on `codex/mainfo-dashboard`, run:

```powershell
.\tools\sync-upstream.ps1
```

This fetches the original repository, merges `upstream/main` and runs the full
`tools/check.sh` checks. If Git reports conflicts, the script stops and leaves
them available for resolution; it never resets or discards local work.
After resolving conflicts, rerun checks before pushing.

To publish a successful synchronization in the same operation:

```powershell
.\tools\sync-upstream.ps1 -Push
```

The script requires the same JDK, Android SDK, Git Bash, Python and Node tools
as the normal build. `JAVA_HOME` and `ANDROID_HOME` should identify the installed
JDK and Android SDK. It does not install software or change the TV.

Equivalent Git commands on other systems:

```sh
git switch codex/mainfo-dashboard
git fetch upstream
git merge --no-edit upstream/main
./tools/check.sh
git push origin codex/mainfo-dashboard
```

The fork's `main` branch initially preserves the original project. The MaInfo
integration lives on its named branch; select that branch when cloning or building:

```sh
git clone --branch codex/mainfo-dashboard https://github.com/Deni7/free-my-tv.git
cd free-my-tv
git remote add upstream https://github.com/simbasjungles/free-my-tv.git
```

## Build and install updates

Keep `freemytv.keystore` and `config.properties` locally; they are ignored by Git.
Use the same signing key for every FreeMyTV update. Source synchronization does
not install an update on the TV: build the APK and install it separately after
checking the changes.

MaInfo's companion changes are maintained in the **private** repository
https://github.com/Deni7/MaInfo on branch **`codex/freemytv-bridge`**.
Its full sources and local delivery patch are not included in this public fork.
See `FREEMYTV.md` in that repository for the provider contract and deployment.
The local delivery and TV setup guide is [integrations/README.md](integrations/README.md).
