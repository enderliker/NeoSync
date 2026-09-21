#!/usr/bin/env python3
# Copyright (c) NeoSync contributors
# SPDX-License-Identifier: LGPL-2.1-only

"""Create an isolated Phase 3 fixture after installing the production server."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import urllib.request

URL = "https://cdn.modrinth.com/data/Wnxd13zP/versions/jo7lDoK4/Clumps-neoforge-1.21.1-19.0.0.1.jar"
SHA256 = "b524ccdace2ef8fd19f5b2074f7de1103ac5065c52553f064c00e098346c293e"

parser = argparse.ArgumentParser(description=__doc__)
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

with urllib.request.urlopen(URL, timeout=30) as response:
    artifact = response.read(18383)
if len(artifact) != 18382 or hashlib.sha256(artifact).hexdigest() != SHA256:
    raise RuntimeError("The acceptance artifact does not match the pinned Clumps release")
(server / "mods").mkdir(exist_ok=True)
(server / "config").mkdir(exist_ok=True)
filename = URL.rsplit("/", 1)[1]
(server / "mods" / filename).write_bytes(artifact)
(server / "config/neosync-server.json").write_text(json.dumps({
    "enabled": True, "displayName": "NeoSync Phase 3 Acceptance", "mode": "https",
    "bindAddress": "127.0.0.1", "port": 8443, "httpsPort": 8443,
    "keyStore": str(root / "server.p12"), "passwordEnvironment": "NEOSYNC_FIXTURE_PASSWORD",
    "files": [{"fileName": filename, "sources": [{"type": "external", "url": URL}]}],
}, indent=2) + "\n")
(server / "server.properties").write_text("server-ip=127.0.0.1\nserver-port=25575\nonline-mode=false\nenable-status=true\nview-distance=2\nsimulation-distance=2\nlevel-name=neosync-phase3-world\n")
for mode in ("install", "resume", "original", "changed", "crash", "space"):
    (root / f"{mode}.properties").write_text(f"mode={mode}\nserver=127.0.0.1:25575\nreport={root}/{mode}-report.txt\nprepared={root}/prepared.txt\nreferenceGame={root}/original\n")
print(f"Fixture ready at {root}. Start the loopback server, then run the graphical client driver.")
