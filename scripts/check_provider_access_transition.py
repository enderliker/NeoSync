#!/usr/bin/env python3
# Copyright (c) NeoSync contributors
# SPDX-License-Identifier: LGPL-2.1-only

"""Check fixture credential injection and removal using the real archive tasks."""

import os
from pathlib import Path
import subprocess
import tempfile
from zipfile import ZipFile

from prepare_release import PROVIDER_ACCESS, ROOT, check_archive_access, credential_needles, require


KEY = b"neosync-build-fixture-not-a-real-key"


def gradle(arguments, environment, succeeds=True):
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    result = subprocess.run([str(wrapper), *arguments, "--max-workers=2", "--console=plain"], cwd=ROOT,
                            env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    require(not any(needle in result.stdout for needle in credential_needles(KEY)), "A fixture build log contains a literal provider credential.")
    if (result.returncode == 0) != succeeds:
        print(result.stdout.decode("utf-8", errors="replace"))
    require((result.returncode == 0) == succeeds, "Provider build transition check failed.")
    if not succeeds:
        require(b"Credential-bearing builds require --no-build-cache --no-configuration-cache." in result.stdout,
                "Credential build did not reject caching explicitly.")


def main():
    environment = os.environ.copy()
    environment.pop("NEOSYNC_CURSEFORGE_KEY_FILE", None)
    tasks = [":neoforge:installerJar", ":neoforge:sourcesJar"]
    private_flags = ["--no-build-cache", "--no-configuration-cache"]
    properties = dict(line.split("=", 1) for line in (ROOT / "gradle.properties").read_text().splitlines()
                      if "=" in line and not line.startswith("#"))
    name = f'NeoSync-{properties["neosync_version"]}-neoforge-{properties["neoforge_base_version"]}'
    libraries = ROOT / "projects/neoforge/build/libs"
    generated = ROOT / "projects/neoforge/build/generated/provider-access" / PROVIDER_ACCESS
    processed = ROOT / "projects/neoforge/build/resources/main" / PROVIDER_ACCESS
    with tempfile.TemporaryDirectory(prefix="neosync-provider-fixture-") as temporary:
        key_path = Path(temporary) / "credential"
        key_path.write_bytes(KEY)
        if os.name != "nt":
            key_path.chmod(0o600)
        with_access = environment | {"NEOSYNC_CURSEFORGE_KEY_FILE": str(key_path)}
        gradle([":neoforge:generateProviderAccess", "--build-cache", "--no-configuration-cache"], with_access, succeeds=False)
        gradle([":neoforge:generateProviderAccess", "--no-build-cache", "--configuration-cache"], with_access, succeeds=False)
        try:
            gradle(tasks + private_flags, with_access)
            require(generated.is_file() and processed.is_file(), "The fixture credential was not generated and processed.")
            with ZipFile(libraries / f"{name}-universal.jar") as universal:
                require(PROVIDER_ACCESS in universal.namelist(), "The fixture runtime is missing provider access.")
            with ZipFile(libraries / f"{name}-sources.jar") as sources:
                require(PROVIDER_ACCESS not in sources.namelist(), "Provider access entered the source archive.")
            with ZipFile(libraries / f"{name}-installer.jar") as installer:
                embedded = [entry for entry in installer.namelist() if entry.startswith("maven/") and entry.endswith("-universal.jar")]
                require(len(embedded) == 1, "The fixture installer has an unexpected runtime inventory.")
                require(installer.read(embedded[0]) == (libraries / f"{name}-universal.jar").read_bytes(),
                        "The fixture installer did not embed the exact runtime.")
            for kind in ("universal", "installer", "sources", "earlydisplay", "earlydisplay-sources"):
                allowed = ((PROVIDER_ACCESS,),) if kind == "universal" else ((embedded[0], PROVIDER_ACCESS),) if kind == "installer" else ()
                check_archive_access((libraries / f"{name}-{kind}.jar").read_bytes(), KEY, allowed)
        finally:
            gradle(tasks + private_flags, environment)
        require(not generated.exists() and not processed.exists(), "A no-key build retained generated provider access.")
        for kind in ("universal", "installer", "sources", "earlydisplay", "earlydisplay-sources"):
            check_archive_access((libraries / f"{name}-{kind}.jar").read_bytes(), None)
    print("PASS: fixture access is excluded from sources, cache-enabled builds fail, and a no-key rebuild removes runtime access.")


if __name__ == "__main__":
    main()
