#!/usr/bin/env python3
# Copyright (c) NeoSync contributors
# SPDX-License-Identifier: LGPL-2.1-only
"""Validate and export NeoSync release assets without distributing installed games."""

import argparse
import base64
import hashlib
import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parent.parent

# Digests of unchanged entries from the pinned upstream 4.0.45 archives.
# Include paths, class/source bytes, module metadata, services, and the font.
EARLYDISPLAY_CONTENT = {
    "earlydisplay": "754302881692ff53928b760068fd0cc3328b051869f24a444c5ddafbdc3d9014",
    "earlydisplay-sources": "5d756005a7a5702d1bf7cd8741b84cecd3e7e683a07e434e14ee4ceae7c4eb0a",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def validate():
    properties = dict(
        line.split("=", 1)
        for line in (ROOT / "gradle.properties").read_text().splitlines()
        if "=" in line and not line.startswith("#")
    )
    version = properties["neosync_version"]
    base = properties["neoforge_base_version"]
    require(re.fullmatch(r"\d+\.\d+\.\d+(?:-(?:alpha|beta|rc)\.\d+)?", version), "Invalid release version.")
    require(re.fullmatch(r"\d+\.\d+\.\d+", base), "Invalid NeoForge base version.")
    source_path = "net/neoforged/neoforge/neosync/protocol/SyncManifest"
    source = (ROOT / f"src/main/java/{source_path}.java").read_bytes()
    require(f'NEOSYNC_VERSION = "{version}"'.encode() in source, "Runtime and build NeoSync versions differ.")
    name = f"NeoSync-{version}-neoforge-{base}"
    installed_version = f"{base}-neosync-{version}"
    maven_path = f"net/neoforged/neoforge/{installed_version}/neoforge-{installed_version}-universal.jar"
    assets = {kind: ROOT / f"projects/neoforge/build/libs/{name}-{kind}.jar"
              for kind in ("installer", "universal", "sources", "earlydisplay", "earlydisplay-sources")}
    for path in assets.values():
        require(path.is_file(), f"Build the missing artifact first: {path.name}")
    for kind in ("universal", "sources"):
        with ZipFile(assets[kind]) as archive:
            require(not any(n.startswith(("net/minecraft/", "com/mojang/", "mcp/")) for n in archive.namelist()),
                    f"Minecraft content found in {kind} artifact.")
            require("META-INF/neosync/provider-access.bin" not in archive.namelist(),
                    f"A provider credential resource is present in the {kind} artifact.")
            require(archive.testzip() is None, f"Corrupt {kind} archive.")
            if kind == "universal":
                require(version.encode() in archive.read(source_path + ".class"), "The compiled runtime has a different NeoSync version.")
                manifest = archive.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "")
                require(f"NeoSync-Version: {version}" in manifest, "Missing NeoSync runtime identity.")
                require(f"NeoForge-Base-Version: {base}" in manifest, "Wrong runtime base identity.")
                metadata = archive.read("META-INF/neoforge.mods.toml").decode()
                require(re.search(r'version\s*=\s*"' + re.escape(base) + '"', metadata), "The mod-facing base version changed.")
                require('logoFile="neosync_logo.png"' in metadata and 'displayName="NeoSync"' in metadata, "Missing NeoSync mod identity.")
                require(archive.read("neosync_logo.png") == (ROOT / "docs/assets/neosync-installer.png").read_bytes(), "Stale mod-list banner.")
                require("neoforged_logo.png" not in archive.namelist(), "Obsolete mod-list logo is still bundled.")
                require(archive.read("neosync/admin/icon.svg") == (ROOT / "docs/assets/neosync-mark.svg").read_bytes(), "Stale administrator panel logo.")
            else:
                require(archive.read(source_path + ".java") == source, "Sources JAR is stale.")
    require(properties["fancy_mod_loader_version"] == "4.0.45", "Review startup branding against the new FML version.")
    early_version = f'4.0.45-neosync-{version}'
    early_path = f"io/github/enderliker/neosync/earlydisplay/{early_version}/earlydisplay-{early_version}.jar"
    graphics = {"neoforged_icon.png": "neosync-icon.png", "squirrel.png": "neosync-icon.png", "fox_running.png": "neosync-startup.png"}
    for kind, expected in EARLYDISPLAY_CONTENT.items():
        with ZipFile(assets[kind]) as archive:
            require(archive.testzip() is None, f"Corrupt {kind} archive.")
            require(len(set(archive.namelist())) == len(archive.namelist()), f"Duplicate {kind} entries.")
            content = hashlib.sha256()
            for entry in sorted(archive.namelist()):
                if not entry.endswith("/") and entry not in {*graphics, "META-INF/LICENSE.txt", "META-INF/earlydisplay-NOTICE.txt"}:
                    content.update(entry.encode() + b"\0" + archive.read(entry))
            require(content.hexdigest() == expected, f"Upstream code or non-branding resources changed in {kind}.")
            for target, source_name in graphics.items():
                require(archive.read(target) == (ROOT / "docs/assets" / source_name).read_bytes(), f"Stale startup graphic: {target}")
            require(archive.read("META-INF/LICENSE.txt") == (ROOT / "LICENSE.txt").read_bytes(), "Missing startup library license.")
            require(archive.read("META-INF/earlydisplay-NOTICE.txt") == (ROOT / "docs/assets/earlydisplay-NOTICE.txt").read_bytes(), "Missing startup attribution.")
    with ZipFile(assets["installer"]) as archive:
        require(archive.testzip() is None, "Corrupt installer archive.")
        require(len(set(archive.namelist())) == len(archive.namelist()), "Duplicate installer entries.")
        require("Main-Class: net.neoforged.neosync.installer.InstallerMain" in archive.read("META-INF/MANIFEST.MF").decode(), "Missing graphical installer entry point.")
        for entry in ("net/neoforged/neosync/installer/InstallerMain.class", "org/sqlite/JDBC.class", "com/google/gson/JsonParser.class", "org/slf4j/LoggerFactory.class", "launchers/prism.png", "launchers/modrinth.png"):
            require(entry in archive.namelist(), f"Missing installer component: {entry}")
        require(next(name for name in archive.namelist() if not name.endswith("/")) == "META-INF/MANIFEST.MF",
                "The executable installer manifest must be the first file.")
        profile = json.loads(archive.read("install_profile.json"))
        launcher = json.loads(archive.read("version.json"))
        require(profile["profile"] == "NeoSync" and profile["version"] == name and launcher["id"] == name,
                "Installer or launcher identity differs from the release.")
        require(profile["minecraft"] == properties["minecraft_version"], "Wrong Minecraft version.")
        require(base64.b64decode(profile["icon"].removeprefix("data:image/png;base64,")) == (ROOT / "docs/assets/neosync-icon.png").read_bytes(), "Stale launcher icon.")
        require(archive.read("big_logo.png") == (ROOT / "docs/assets/neosync-installer.png").read_bytes(), "Stale installer banner.")
        for target, source_name in {"neoforged_16x16.png": "neosync-icon-16.png", "neoforged_background_16x16.png": "neosync-icon-16.png",
                                    "neoforged_background_32x32.png": "neosync-icon-32.png", "neoforged_background_128x128.png": "neosync-icon.png"}.items():
            require(archive.read("icons/" + target) == (ROOT / "docs/assets" / source_name).read_bytes(), "Stale installer window icon.")
        require(archive.read("maven/" + maven_path) == assets["universal"].read_bytes(), "Embedded universal JAR differs.")
        own_libraries = [lib for lib in profile["libraries"] if lib["name"].startswith("net.neoforged:neoforge:")]
        require(len(own_libraries) == 1, "Unexpected fork runtime libraries.")
        artifact = own_libraries[0]["downloads"]["artifact"]
        runtime_url = f"https://github.com/enderliker/NeoSync/releases/download/{name}/{name}-universal.jar"
        require(artifact["url"] == runtime_url and artifact["path"] == maven_path, "Runtime location differs from this NeoSync release.")
        require(artifact["sha1"] == hashlib.sha1(assets["universal"].read_bytes()).hexdigest(), "Embedded SHA-1 differs.")
        require(archive.read("maven/" + early_path) == assets["earlydisplay"].read_bytes(), "Embedded startup library differs.")
        early_url = f"https://github.com/enderliker/NeoSync/releases/download/{name}/{name}-earlydisplay.jar"
        for document in (profile, launcher):
            require(not any(lib["name"].startswith("net.neoforged.fancymodloader:earlydisplay:") for lib in document["libraries"]),
                    "An upstream startup library remains in the installed profile.")
            replacements = [lib for lib in document["libraries"] if lib["name"].startswith("io.github.enderliker.neosync:earlydisplay:")]
            require(len(replacements) == 1 and replacements[0]["name"] == f"io.github.enderliker.neosync:earlydisplay:{early_version}", "Wrong startup library identity.")
            require(replacements[0]["downloads"]["artifact"] == {
                "path": early_path, "url": early_url, "size": assets["earlydisplay"].stat().st_size,
                "sha1": hashlib.sha1(assets["earlydisplay"].read_bytes()).hexdigest(),
            }, "Wrong startup library download metadata.")
        args = launcher["arguments"]["game"]
        require(args[args.index("--fml.neoForgeVersion") + 1] == installed_version, "Wrong FML installation version.")
        for side in ("client", "server"):
            require(profile["data"]["PATCHED"][side] == f"[net.neoforged:neoforge:{installed_version}:{side}]",
                    "Patched game output is not isolated.")
            require(archive.getinfo(f"data/{side}.lzma").file_size > 0, f"Missing {side} binary patches.")
        for platform in ("unix", "win"):
            server_args = archive.read(f"data/{platform}_args.txt").decode()
            require(installed_version in server_args and early_path in server_args, "Stale server arguments.")
            require("net/neoforged/fancymodloader/earlydisplay/" not in server_args, "Upstream startup path remains in server arguments.")
        require(not any(n.startswith("maven/") and n not in {"maven/" + maven_path, "maven/" + early_path} and not n.endswith("/") for n in archive.namelist()),
                "Unexpected embedded Maven artifacts.")
    return name, properties, assets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="Validate built artifacts without exporting or requiring a clean tree.")
    mode.add_argument("--local", action="store_true", help="Export an unpublished candidate from a clean local commit without requiring a push.")
    parser.add_argument("--windows-exe", type=Path, help="Include the Windows wrapper built from this exact installer.")
    args = parser.parse_args()
    name, properties, assets = validate()
    if args.windows_exe:
        exe = args.windows_exe.resolve()
        require(exe.is_file() and exe.name == name + "-installer.exe", "Wrong Windows installer name.")
        require(exe.read_bytes()[:2] == b"MZ", "The Windows installer is not a PE executable.")
        require(Path(str(exe) + ".jar.sha256").read_text().strip() == sha256(assets["installer"]), "The executable wraps a different installer JAR.")
        require(assets["installer"].read_bytes() in exe.read_bytes(), "The executable is missing the exact embedded installer.")
        assets["windows-installer"] = exe
    if args.check:
        print(f"PASS: {name}: installer, embedded libraries, isolated paths, source identity, branding, and unchanged FML code.")
        return
    require(not git("status", "--porcelain", "--untracked-files=no"), "Commit all tracked changes before exporting a release.")
    commit = git("rev-parse", "HEAD")
    if not args.local:
        require(git("branch", "-r", "--contains", commit), "Push the source commit before exporting.")
    parent = ROOT / ("build/neosync-candidates" if args.local else "build/neosync-release")
    parent.mkdir(parents=True, exist_ok=True)
    target = parent / name
    require(not target.exists(), f"Refusing to overwrite exported release: {target}")
    with tempfile.TemporaryDirectory(prefix=".export-", dir=parent) as temp:
        staging = Path(temp)
        records = []
        for path in assets.values():
            dest = staging / path.name
            shutil.copyfile(path, dest)
            records.append({"name": dest.name, "size": dest.stat().st_size, "sha256": sha256(dest)})
        manifest = {
            "release": name, "sourceCommit": commit, "publication": "local-candidate" if args.local else "release-assets",
            "neoSyncVersion": properties["neosync_version"], "neoForgeBaseVersion": properties["neoforge_base_version"],
            "minecraftVersion": properties["minecraft_version"], "javaVersion": properties["java_version"],
            "protocolVersion": 1, "artifacts": records,
        }
        manifest_path = staging / "release-manifest.json"
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
        checksums = [f'{record["sha256"]}  {record["name"]}' for record in records]
        checksums.append(f"{sha256(manifest_path)}  release-manifest.json")
        (staging / "SHA256SUMS").write_text("\n".join(checksums) + "\n")
        staging.rename(target)
    print(f"Exported {name} from {commit} to {target}")


if __name__ == "__main__":
    main()
