#!/usr/bin/env python3
# Copyright (c) NeoSync contributors
# SPDX-License-Identifier: LGPL-2.1-only

"""Import an installed NeoSync runtime into SKlauncher 4.0 Beta or Modrinth App."""

import argparse
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import tempfile
import time

from configure_prism import read_json, safe_path


def bounded_record(path):
    path = safe_path(path)
    if not path.is_file() or path.stat().st_size > 1024 * 1024:
        raise ValueError('Missing or oversized launcher record')
    with path.open('rb') as stream:
        data = stream.read(1024 * 1024 + 1)
    if len(data) > 1024 * 1024:
        raise ValueError('Launcher record exceeds its size limit')
    return data


def digest(path):
    path = safe_path(path)
    if not path.is_file() or path.stat().st_size > 512 * 1024 * 1024:
        raise ValueError('Missing or oversized runtime library: ' + path.name)
    result = hashlib.sha256()
    with path.open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            result.update(chunk)
    return result.digest()


def atomic_json(path, original, data):
    output = (json.dumps(data, indent=2) + '\n').encode('utf-8')
    if len(output) > 1024 * 1024:
        raise ValueError('Launcher inventory exceeds its size limit')
    descriptor, name = tempfile.mkstemp(prefix='.neosync-', dir=path.parent)
    stage = Path(name)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(output)
            stream.flush()
            os.fsync(stream.fileno())
        if bounded_record(path) != original:
            raise ValueError('The launcher changed its inventory; retry with the launcher closed')
        stage.replace(path)
    finally:
        stage.unlink(missing_ok=True)


def register(root, launcher, instance_id, version, name, launch_overrides=None):
    if launcher == 'sklauncher-beta':
        path = safe_path(root / 'instances.json')
        original = bounded_record(path)
        data = json.loads(original)
        if not isinstance(data.get('instances'), list):
            raise ValueError('Unsupported SKlauncher instance inventory')
        if any(not isinstance(entry, dict) for entry in data['instances']):
            raise ValueError('Malformed SKlauncher instance inventory')
        existing = [entry for entry in data['instances'] if entry.get('id') == instance_id]
        if existing:
            if len(existing) != 1 or any(existing[0].get(key) != value for key, value in
                                       {'versionId': version, 'minecraftVersion': version,
                                        'type': 'custom', 'gameType': 'custom', 'compatibilityMode': True}.items()):
                raise ValueError('The NeoSync instance was edited; it was not overwritten')
            return
        data['instances'].append({'id': instance_id, 'name': name, 'type': 'custom',
                                  'versionId': version, 'gameType': 'custom', 'minecraftVersion': version,
                                  'directory': str(root / 'instances' / instance_id), 'compatibilityMode': True,
                                  'createdAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                                  'playTime': 0, 'sessionCount': 0})
        atomic_json(path, original, data)
        return
    database = safe_path(root / 'app.db')
    if not database.is_file():
        raise ValueError('Run Modrinth App once before importing NeoSync')
    with closing(sqlite3.connect(database.as_uri() + '?mode=rw', uri=True, timeout=3)) as connection, connection:
        connection.execute('PRAGMA foreign_keys=ON')
        connection.execute('BEGIN IMMEDIATE')
        instance_key = 'local:' + instance_id
        existing = connection.execute('SELECT i.path,c.loader_version,c.game_version,c.loader FROM instances i LEFT JOIN instance_content_sets c ON c.id=i.applied_content_set_id WHERE i.id=?', (instance_key,)).fetchone()
        if existing:
            overrides = connection.execute('SELECT json(overrides) FROM instance_launch_overrides WHERE instance_id=?', (instance_key,)).fetchone()
            if existing != (instance_id, version, '1.21.1', 'neoforge') or not overrides or json.loads(overrides[0]).get('extra_launch_args') != launch_overrides['extra_launch_args']:
                raise ValueError('The NeoSync instance was edited; it was not overwritten')
            return
        if connection.execute('SELECT 1 FROM instances WHERE path=?', (instance_id,)).fetchone():
            raise ValueError('The target instance directory is already registered')
        content_id = 'content-set:' + instance_id
        now = int(time.time())
        connection.execute("INSERT INTO instances(id,path,applied_content_set_id,install_stage,launcher_feature_version,update_channel,name,created,modified) VALUES(?,?,?,'not_installed','migrated_launch_hooks','release',?,?,?)", (instance_key, instance_id, content_id, name, now, now))
        connection.execute("INSERT INTO instance_content_sets(id,instance_id,name,source_kind,status,game_version,loader,loader_version,created,modified) VALUES(?,?,'Default','local','available','1.21.1','neoforge',?,?,?)", (content_id, instance_key, version, now, now))
        connection.execute("INSERT INTO instance_links(instance_id,link_kind) VALUES(?,'unmanaged')", (instance_key,))
        connection.execute('INSERT INTO instance_launch_overrides(instance_id,overrides) VALUES(?,json(?))', (instance_key, json.dumps(launch_overrides)))
        connection.execute('INSERT INTO instance_sync_preferences(instance_id,feature,enabled) SELECT ?,feature,0 FROM sync_feature_settings', (instance_key,))


def configure(installation, root, launcher, version=None):
    installation, root = safe_path(installation), safe_path(root)
    if not installation.is_dir() or not root.is_dir():
        raise ValueError('Select an installed NeoSync client and an initialized launcher directory')
    records = sorted((installation / 'versions').glob('NeoSync-*/*.json'))
    if version:
        records = [path for path in records if path.stem == version]
    if len(records) != 1:
        raise ValueError('Select one installed NeoSync version with --version')
    profile = read_json(safe_path(records[0]))
    version = profile['id']
    match = re.fullmatch(r'NeoSync-([0-9A-Za-z.+-]+)-neoforge-([0-9.]+)', version)
    if not match or profile['inheritsFrom'] != '1.21.1' or profile['mainClass'] != 'cpw.mods.bootstraplauncher.BootstrapLauncher':
        raise ValueError('The installation is not a supported NeoSync 1.21.1 runtime')
    game = profile['arguments']['game']
    jvm = profile['arguments']['jvm']
    if not isinstance(game, list) or not isinstance(jvm, list) or any(not isinstance(value, str) for value in jvm):
        raise ValueError('Unsupported installed NeoSync launch arguments')
    runtime_version = match[2] + '-neosync-' + match[1]
    if game[game.index('--fml.neoForgeVersion') + 1] != runtime_version:
        raise ValueError('NeoSync requires its own isolated installed runtime')
    library_argument = '-DlibraryDirectory=' + str(safe_path(installation / 'libraries'))
    root_argument = '-Dneosync.launcher.root=' + str(root)
    kind_argument = '-Dneosync.launcher.kind=' + launcher
    launch_overrides = None
    if launcher == 'modrinth':
        # Modrinth strips whitespace from metadata JVM arguments before expansion.
        # Instance overrides are appended verbatim and preserve local paths with spaces.
        profile['arguments']['jvm'] = [value for value in profile['arguments']['jvm'] if not value.startswith('-DlibraryDirectory=')]
        launch_overrides = {'extra_launch_args': [library_argument, root_argument, kind_argument]}
        profile['neosyncLaunchOverrides'] = launch_overrides
    else:
        profile['arguments']['jvm'] = [library_argument if value.startswith('-DlibraryDirectory=') else value for value in profile['arguments']['jvm']]
        profile['arguments']['jvm'].extend([root_argument, kind_argument])
    if launcher == 'sklauncher-beta':
        profile['arguments']['jvm'] = [value + ',1.21.1.jar' if value.startswith('-DignoreList=') else value for value in profile['arguments']['jvm']]
    if launcher == 'modrinth':
        profile['arguments']['jvm'].extend(['-Dminecraft.launcher.brand=theseus'])
    if not isinstance(profile['libraries'], list) or len(profile['libraries']) > 256:
        raise ValueError('Installed library count exceeds the limit')
    metadata_root = safe_path(root / 'meta' if launcher == 'modrinth' else root)
    libraries = safe_path(installation / 'libraries')
    copies = {}
    total = 0
    for library in profile['libraries']:
        artifact = library['downloads']['artifact']
        relative = Path(artifact['path'])
        if relative.is_absolute() or '..' in relative.parts or ':' in str(relative) or relative.suffix != '.jar':
            raise ValueError('Invalid installed library path')
        source = safe_path(libraries / relative)
        if not source.is_file() or source.stat().st_size > 512 * 1024 * 1024:
            raise ValueError('Missing or oversized installed library: ' + source.name)
        data = source.read_bytes()
        if len(data) != artifact['size'] or hashlib.sha1(data).hexdigest() != artifact['sha1']:
            raise ValueError('Installed library failed integrity verification: ' + source.name)
        total += len(data)
        if total > 512 * 1024 * 1024:
            raise ValueError('Installed runtime exceeds the copy limit')
        copies[relative] = source
    runtime_dir = Path('net/neoforged/neoforge') / runtime_version
    for suffix in ['universal', 'client']:
        relative = runtime_dir / f'neoforge-{runtime_version}-{suffix}.jar'
        source = safe_path(libraries / relative)
        if not source.is_file() or source.stat().st_size > 512 * 1024 * 1024:
            raise ValueError('Complete the NeoSync client installation first')
        if relative not in copies:
            total += source.stat().st_size
            if total > 512 * 1024 * 1024:
                raise ValueError('Installed runtime exceeds the copy limit')
        copies[relative] = source
    # Both applications have their own library root; preserve the installed fork's
    # Maven paths so FML resolves its universal and generated client artifacts.
    for relative, source in copies.items():
        destination = safe_path(metadata_root / 'libraries' / relative)
        if destination.exists():
            if digest(destination) != digest(source):
                raise ValueError('A launcher library differs from the installed runtime: ' + destination.name)
    modrinth = launcher == 'modrinth'
    target_version = '1.21.1-' + version if modrinth else version
    vanilla = read_json(safe_path(installation / 'versions/1.21.1/1.21.1.json'))
    merged = dict(vanilla)
    merged.update(profile)
    merged.pop('inheritsFrom', None)
    merged['arguments'] = {kind: vanilla['arguments'].get(kind, []) + profile['arguments'].get(kind, []) for kind in ['game', 'jvm']}
    merged['libraries'] = vanilla['libraries'] + profile['libraries']
    profile = merged
    profile['id'] = target_version
    if not modrinth:
        profile['jar'] = '1.21.1'
    target = safe_path(metadata_root / 'versions' / target_version)
    output = (json.dumps(profile, indent=2) + '\n').encode('utf-8')
    if len(output) > 1024 * 1024:
        raise ValueError('Runtime metadata exceeds its size limit')
    instance_id = 'neosync-' + hashlib.sha256(version.encode()).hexdigest()[:24]
    instance = safe_path(root / ('profiles' if modrinth else 'instances') / instance_id)
    if instance.exists() and (not instance.is_dir() or not target.exists()):
        raise ValueError('The target instance directory already exists; it was not reused')
    if target.exists() and bounded_record(target / (target_version + '.json')) != output:
        raise ValueError('The target NeoSync runtime was edited; it was not overwritten')
    for relative, source in copies.items():
        destination = safe_path(metadata_root / 'libraries' / relative)
        if destination.exists():
            continue
        destination.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary = tempfile.mkstemp(prefix='.neosync-', dir=destination.parent)
        stage = Path(temporary)
        try:
            with source.open('rb') as stream, os.fdopen(descriptor, 'wb') as stream_out:
                shutil.copyfileobj(stream, stream_out)
            if digest(stage) != digest(source):
                raise ValueError('A runtime library changed during import')
            # Hard-link publication fails if another process already created the target.
            os.link(stage, destination)
        finally:
            stage.unlink(missing_ok=True)
    created = False
    if not target.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        stage = Path(tempfile.mkdtemp(prefix='.neosync-', dir=target.parent))
        try:
            (stage / (target_version + '.json')).write_bytes(output)
            stage.rename(target)
            created = True
        finally:
            if stage.exists():
                shutil.rmtree(stage)
    created_instance = not instance.exists()
    try:
        instance.mkdir(parents=True, exist_ok=True)
        register(root, launcher, instance_id, version, version, launch_overrides)
    except Exception:
        if created:
            shutil.rmtree(target)
        if created_instance and instance.is_dir():
            instance.rmdir()
        raise
    return instance_id


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--installation', type=Path, required=True)
    parser.add_argument('--launcher-root', type=Path, required=True)
    parser.add_argument('--launcher', choices=['sklauncher-beta', 'modrinth'], required=True)
    parser.add_argument('--version')
    args = parser.parse_args()
    try:
        instance = configure(args.installation, args.launcher_root, args.launcher, args.version)
    except (OSError, ValueError, KeyError, TypeError, IndexError, sqlite3.Error) as error:
        parser.exit(1, 'NeoSync setup failed: ' + str(error) + '\n')
    print('Created NeoSync instance: ' + instance)
    print('Reopen your launcher and select the NeoSync instance. Keep the source installation in place.')


if __name__ == '__main__':
    main()
