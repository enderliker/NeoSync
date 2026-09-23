#!/usr/bin/env python3
# Copyright (c) NeoSync contributors
# SPDX-License-Identifier: LGPL-2.1-only

"""Create an isolated external-download or private-hosting acceptance fixture."""

import argparse
import hashlib
import json
import os
import uuid
from pathlib import Path
import shutil
import subprocess
import urllib.request

URL = "https://cdn.modrinth.com/data/Wnxd13zP/versions/jo7lDoK4/Clumps-neoforge-1.21.1-19.0.0.1.jar"
SHA256 = "b524ccdace2ef8fd19f5b2074f7de1103ac5065c52553f064c00e098346c293e"

parser = argparse.ArgumentParser(description=__doc__)
choices = parser.add_mutually_exclusive_group()
choices.add_argument("--manual-fixture", action="store_true", help="Test manual import with synthetic CurseForge metadata and a controlled browser launcher; not live provider acceptance")
choices.add_argument("--providers", action="store_true", help="Resolve the exact Clumps bytes through live Modrinth metadata")
choices.add_argument("--hosting", action="store_true", help="Generate a unique mod solely for this local server; never download a third-party fixture")
parser.add_argument("--root", required=True, type=Path)
parser.add_argument("--jdk", required=True, type=Path)
parser.add_argument("--server", required=True, type=Path, help="Disposable installed production server")
args = parser.parse_args()
root, jdk, server = args.root.resolve(), args.jdk.resolve(), args.server.resolve()
root.mkdir(parents=True, exist_ok=False)
(root / "original").mkdir()
(root / "agent").mkdir()
source = Path(__file__).with_name("ClientDriver.java")

with (root / "setup.log").open("w") as log:
    def run(tool, *arguments):
        subprocess.run([str(jdk / "bin" / tool), *map(str, arguments)], check=True, stdout=log, stderr=subprocess.STDOUT)

    run("javac", "-d", root / "agent", source)
    (root / "agent-manifest.mf").write_text("Premain-Class: net.neosync.acceptance.ClientDriver\n\n")
    run("jar", "cfm", root / "driver.jar", root / "agent-manifest.mf", "-C", root / "agent", ".")
    run("keytool", "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-validity", "2", "-dname", "CN=localhost",
        "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12", "-keystore", root / "server.p12", "-storepass", "fixture-password", "-noprompt")
    run("keytool", "-exportcert", "-alias", "fixture", "-keystore", root / "server.p12", "-storepass", "fixture-password", "-file", root / "server.cer")
    shutil.copyfile(jdk / "lib/security/cacerts", root / "truststore")
    run("keytool", "-importcert", "-alias", "neosync-fixture", "-keystore", root / "truststore", "-storepass", "changeit", "-file", root / "server.cer", "-noprompt")

(server / "mods").mkdir(exist_ok=True)
(server / "config").mkdir(exist_ok=True)
if args.hosting:
    if any((server / "mods").iterdir()):
        raise RuntimeError("Hosting acceptance requires an empty disposable server mods directory")
    mod_id = "private_fixture_" + uuid.uuid4().hex[:12]
    filename = mod_id + ".jar"
    display_name = "Private server fixture"
    classes = root / "private-mod"
    (classes / "META-INF").mkdir(parents=True)
    java_source = root / "PrivateServerMod.java"
    java_source.write_text('package fixture;\n@net.neoforged.fml.common.Mod("' + mod_id + '")\npublic final class PrivateServerMod {}\n')
    classpath = os.pathsep.join(map(str, (server / "libraries").rglob("*.jar")))
    subprocess.run([str(jdk / "bin/javac"), "-proc:none", "-classpath", classpath, "-d", str(classes), str(java_source)], check=True)
    (classes / "META-INF/neoforge.mods.toml").write_text(
        'modLoader="javafml"\nloaderVersion="[4,)"\nlicense="Private acceptance fixture; no external distribution"\n'
        '[[mods]]\nmodId="' + mod_id + '"\nversion="1.0"\ndisplayName="' + display_name + '"\n')
    subprocess.run([str(jdk / "bin/jar"), "cf", str(server / "mods" / filename), "-C", str(classes), "."], check=True)
    artifact = (server / "mods" / filename).read_bytes()
    selection = {"fileName": filename, "sources": [{"type": "server"}], "hosting": {
        "authoredByAdministrator": True, "exclusiveToServer": True,
        "distributionRights": True, "sha256": hashlib.sha256(artifact).hexdigest(),
    }}
    phase = "Phase 4"
    expected_source = "Provided by the server NeoSync Phase 4 Acceptance (127.0.0.1:25575) via https://127.0.0.1:8443"
else:
    with urllib.request.urlopen(URL, timeout=30) as response:
        artifact = response.read(18383)
    if len(artifact) != 18382 or hashlib.sha256(artifact).hexdigest() != SHA256:
        raise RuntimeError("The acceptance artifact does not match the pinned Clumps release")
    filename = URL.rsplit("/", 1)[1]
    (server / "mods" / filename).write_bytes(artifact)
    selection = {"fileName": filename, "sources": [{"type": "external", "url": URL}]}
    mod_id, display_name, expected_source, phase = "clumps", "Clumps", "cdn.modrinth.com", "Phase 3"
    if args.providers:
        selection = {"fileName": filename, "resolveProviders": True}
        phase = "Phase 5"
    if args.manual_fixture:
        page = "https://www.curseforge.com/minecraft/mc-mods/neosync-test-fixture/download/456"
        selection = {"fileName": filename, "sources": [{"type": "external", "url": page,
            "provider": {"id": "curseforge", "projectId": "123", "fileId": "456",
                         "sha1": hashlib.sha1(artifact).hexdigest(), "manual": True}}]}
        expected_source, phase = "www.curseforge.com", "Phase 5 manual fixture"
        (root / "browser-bin").mkdir()
        (root / "xdg-config").mkdir()
        (root / "Relocated Downloads").mkdir()
        (root / "xdg-config/user-dirs.dirs").write_text('XDG_DOWNLOAD_DIR="' + str(root / "Relocated Downloads") + '"\n')
        import shlex
        browser = root / "browser-bin/xdg-open"
        browser.write_text("#!/bin/sh\nset -eu\n[ \"$1\" = " + shlex.quote(page) + " ]\n" +
            "printf 'Approved fixture page opened\\n' >> " + shlex.quote(str(root / "browser-report.txt")) + "\n" +
            "cp " + shlex.quote(str(server / "mods" / filename)) + " " + shlex.quote(str(root / "Relocated Downloads/mod (1).jar.part")) + "\n" +
            "mv " + shlex.quote(str(root / "Relocated Downloads/mod (1).jar.part")) + " " + shlex.quote(str(root / "Relocated Downloads/mod (1).jar")) + "\n")
        browser.chmod(0o700)
(server / "config/neosync-server.json").write_text(json.dumps({
    "enabled": True, "displayName": f"NeoSync {phase} Acceptance", "mode": "https",
    "bindAddress": "127.0.0.1", "port": 8443, "httpsPort": 8443,
    "keyStore": str(root / "server.p12"), "passwordEnvironment": "NEOSYNC_FIXTURE_PASSWORD",
    "files": [selection],
    "hosting": {"enabled": args.hosting},
}, indent=2) + "\n")
(server / "server.properties").write_text("server-ip=127.0.0.1\nserver-port=25575\nonline-mode=false\nenable-status=true\nview-distance=2\nsimulation-distance=2\nlevel-name=neosync-acceptance-world\n")
for mode in ("install", "resume", "original", "changed", "crash", "space"):
    (root / f"{mode}.properties").write_text(f"mode={mode}\nserver=127.0.0.1:25575\nreport={root}/{mode}-report.txt\nprepared={root}/prepared.txt\nreferenceGame={root}/original\nexpectedModId={mod_id}\nexpectedName={display_name}\nexpectedSource={expected_source}\nexpectProvider={str(args.providers).lower()}\nmanualFixture={root if args.manual_fixture else ''}\n")
print(f"Fixture ready at {root}. Start the loopback server, then run the graphical client driver.")
