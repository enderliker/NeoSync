#!/usr/bin/env python3
# Copyright (c) NeoSync contributors
# SPDX-License-Identifier: LGPL-2.1-only

"""Create a local Prism instance using an already installed, versioned NeoSync runtime."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import zipfile


def safe_path(path):
    path = Path(path).absolute()
    if any(ord(c) < 32 for c in str(path)) or '${' in str(path):
        raise ValueError('Unsupported control character or placeholder in a local path')
    for part in [path, *path.parents]:
        if part.is_symlink():
            raise ValueError('Symbolic links are not supported in launcher installation paths')
    return path


def read_json(path):
    if not path.is_file() or path.stat().st_size > 1024 * 1024:
        raise ValueError('Missing or oversized installed version metadata')
    return json.loads(path.read_text(encoding='utf-8'))


def configure(installation, prism_root, java, executable, version=None, instance_id=None):
    installation, prism_root = safe_path(installation), safe_path(prism_root)
    java, executable = safe_path(java), Path(executable).absolute()
    if not java.is_file() or not executable.is_file():
        raise ValueError('Select an existing Java 21 executable and Prism executable')
    versions = sorted((installation / 'versions').glob('NeoSync-*/*.json'))
    if version:
        versions = [path for path in versions if path.stem == version]
    if len(versions) != 1:
        raise ValueError('Select one installed NeoSync version with --version')
    profile = read_json(safe_path(versions[0]))
    name = profile['id']
    match = re.fullmatch(r'NeoSync-([0-9A-Za-z.+-]+)-neoforge-([0-9.]+)', name)
    if not match or profile['inheritsFrom'] != '1.21.1' or profile['mainClass'] != 'cpw.mods.bootstraplauncher.BootstrapLauncher':
        raise ValueError('The installation is not a supported NeoSync 1.21.1 runtime')
    instance_id = instance_id or name
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,127}', instance_id):
        raise ValueError('Use a simple instance identifier with letters, digits, dots, dashes and underscores')
    instances = safe_path(prism_root / 'instances')
    instances.mkdir(parents=True, exist_ok=True)
    target = instances / instance_id
    if target.exists():
        raise FileExistsError('The target instance already exists; choose a new instance ID')
    libraries = safe_path(installation / 'libraries')
    if len(profile['libraries']) > 256:
        raise ValueError('Installed library count exceeds the limit')
    stage = Path(tempfile.mkdtemp(prefix='.neosync-', dir=instances))
    try:
        (stage / 'libraries').mkdir()
        (stage / 'patches').mkdir()
        (stage / '.minecraft').mkdir()
        entries = []
        fingerprints = {}
        for library in profile['libraries']:
            artifact = library['downloads']['artifact']
            relative = Path(artifact['path'])
            if relative.is_absolute() or '..' in relative.parts or relative.suffix != '.jar':
                raise ValueError('Invalid installed library path')
            source = safe_path(libraries / relative)
            if not source.is_file() or source.stat().st_size > 512 * 1024 * 1024:
                raise ValueError('Missing or oversized installed library: ' + source.name)
            data = source.read_bytes()
            if len(data) != artifact['size'] or hashlib.sha1(data).hexdigest() != artifact['sha1']:
                raise ValueError('Installed library failed integrity verification: ' + source.name)
            digest = hashlib.sha256(data).hexdigest()
            if source.name in fingerprints and fingerprints[source.name] != digest:
                raise ValueError('Conflicting local library filenames')
            fingerprints[source.name] = digest
            (stage / 'libraries' / source.name).write_bytes(data)
            entries.append({'name': library['name'], 'MMC-hint': 'local'})
        arguments = profile['arguments']['game']
        loader = arguments[arguments.index('--fml.neoForgeVersion') + 1]
        if '-neosync-' + match[1] not in loader:
            raise ValueError('NeoSync must use an isolated installed runtime path')
        universal = safe_path(libraries / f'net/neoforged/neoforge/{loader}/neoforge-{loader}-universal.jar')
        patched = safe_path(libraries / f'net/neoforged/neoforge/{loader}/neoforge-{loader}-client.jar')
        if not universal.is_file() or not patched.is_file():
            raise ValueError('Complete the NeoSync client installation before configuring Prism')
        with zipfile.ZipFile(universal) as source, zipfile.ZipFile(stage / 'neosync-launcher-bridge.jar', 'w') as bridge:
            for cls in ['LauncherBridge', 'LauncherBridge$Verifier']:
                path = 'net/neoforged/neoforge/neosync/launcher/' + cls + '.class'
                bridge.writestr(path, source.read(path))
        jvm = [arg.replace('${library_directory}', libraries.as_posix()).replace('${classpath_separator}', os.pathsep)
               .replace('${version_name}', 'minecraft-1.21.1-client') for arg in profile['arguments']['jvm']]
        # BootstrapLauncher requires module-path and classpath entries to refer to the same physical JAR.
        module_index = jvm.index('-p') + 1
        jvm[module_index] = os.pathsep.join((target / 'libraries' / Path(part).name).as_posix() for part in jvm[module_index].split(os.pathsep))
        jvm.append('-Dneosync.launcher.config=' + (target / 'neosync-launcher.json').as_posix())
        vanilla = '--username ${auth_player_name} --version ${version_name} --gameDir ${game_directory} --assetsDir ${assets_root} --assetIndex ${assets_index_name} --uuid ${auth_uuid} --accessToken ${auth_access_token} --userType ${user_type} --versionType ${version_type}'
        component = {'formatVersion': 1, 'uid': 'org.neosync', 'name': 'NeoSync', 'version': match[1],
                     'requires': [{'uid': 'net.minecraft', 'equals': '1.21.1'}], 'mainClass': profile['mainClass'],
                     'minecraftArguments': vanilla + ' ' + ' '.join(arguments), 'libraries': entries, '+jvmArgs': jvm}
        pack = {'formatVersion': 1, 'components': [{'uid': 'net.minecraft', 'version': '1.21.1', 'important': True}, {'uid': 'org.neosync', 'version': match[1]}]}
        descriptor = {'schemaVersion': 1, 'kind': 'prism', 'version': match[1], 'neoForgeVersion': match[2],
                      'root': prism_root.as_posix(), 'instance': target.as_posix(), 'executable': str(executable),
                      'java': java.as_posix(), 'libraries': fingerprints, 'component': component,
                      'bridgeSha256': hashlib.sha256((stage / 'neosync-launcher-bridge.jar').read_bytes()).hexdigest()}
        for path, data in [(stage / 'mmc-pack.json', pack), (stage / 'patches/org.neosync.json', component), (stage / 'neosync-launcher.json', descriptor)]:
            path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')
        (stage / 'instance.cfg').write_text('[General]\nInstanceType=OneSix\nname=' + name + '\niconKey=default\nOverrideJavaLocation=true\nJavaPath=' + java.as_posix() + '\nOverrideMemory=true\nMaxMemAlloc=2048\nMinMemAlloc=512\n', encoding='utf-8')
        stage.rename(target)
        return target
    finally:
        if stage.exists():
            shutil.rmtree(stage)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--installation', type=Path, required=True, help='Client directory where the NeoSync installer completed successfully')
    parser.add_argument('--prism-root', type=Path, required=True, help='Prism application root containing instances/; no accounts are read or copied')
    parser.add_argument('--java', type=Path, required=True, help='Java 21 executable')
    parser.add_argument('--prism-executable', type=Path, default=shutil.which('prismlauncher'), help='Prism executable, required if it is not on PATH')
    parser.add_argument('--version', help='Exact NeoSync installed version ID when more than one exists')
    parser.add_argument('--instance-id', help='New instance directory name; existing instances are never replaced')
    args = parser.parse_args()
    if not args.prism_executable:
        parser.error('Specify --prism-executable')
    try:
        target = configure(args.installation, args.prism_root, args.java, args.prism_executable, args.version, args.instance_id)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, 'NeoSync setup failed: ' + str(error) + '\n')
    print('Created local Prism instance: ' + str(target))
    print('Keep the source NeoSync installation in place. Do not distribute this local runtime as a modpack.')


if __name__ == '__main__':
    main()
