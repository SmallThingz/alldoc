#!/usr/bin/env python3
"""Regenerate exact-version Android PDF dependency notices from locked Cargo packages."""
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / 'native/pdf'
# These releases declare MIT in Cargo.toml but contain no standalone license in
# either the published package or their exact upstream Git tree. Keep that fact
# explicit instead of fabricating an upstream copyright notice.
DECLARATIONS = {
    ('pdf-extract', '0.12.1'): '47b792ba04c5f3b6e478072b2efb8e1a2a06ec7e',
    ('adobe-cmap-parser', '0.4.1'): 'ac107d55f0d31a9d47955082238ab9e4fb157cdb',
    ('type1-encoding-parser', '0.1.1'): '55d193e28c9a5909be02a5b1f91fd3f76b076747',
}


def digest(value):
    return hashlib.sha256(value).hexdigest()


def main():
    packages = {}
    for target in ('aarch64-linux-android', 'x86_64-linux-android'):
        metadata = json.loads(subprocess.check_output([
            'cargo', 'metadata', '--locked', '--offline', '--manifest-path',
            str(BASE / 'Cargo.toml'), '--format-version', '1', '--filter-platform', target], cwd=ROOT))
        nodes = {node['id']: node for node in metadata['resolve']['nodes']}
        seen = set()
        todo = [metadata['resolve']['root']]
        while todo:
            key = todo.pop()
            if key in seen: continue
            seen.add(key)
            todo.extend(nodes[key]['dependencies'])
        for package in metadata['packages']:
            if package['id'] in seen and package['source'] is not None:
                packages[(package['name'], package['version'])] = package

    output = {}
    inventory = []
    for key, package in sorted(packages.items()):
        name, version = key
        directory = Path(package['manifest_path']).parent
        vcs_path = directory / '.cargo_vcs_info.json'
        vcs = json.loads(vcs_path.read_text()) if vcs_path.exists() else {}
        revision = vcs.get('git', {}).get('sha1')
        item = dict(name=name, version=version, license=package['license'],
                    source=package.get('repository'), revision=revision,
                    manifestSha256=digest((directory / 'Cargo.toml.orig').read_bytes()), notices=[])
        files = [file for file in directory.iterdir() if file.is_file() and
                 file.name.upper().startswith(('LICENSE', 'COPYING', 'NOTICE', 'AUTHORS'))]
        if key in DECLARATIONS:
            if revision != DECLARATIONS[key] or package['license'] != 'MIT':
                raise RuntimeError(f'Upstream declaration changed for {name} {version}')
            authors = ', '.join(package['authors'])
            notice = (
                f'Package: {name} {version}\n'
                f'Upstream author declaration: {authors}\n'
                f'Upstream manifest: {package["repository"]}/blob/{revision}/Cargo.toml\n'
                'Declared license: MIT\n\n'
                'This upstream revision supplies the MIT SPDX declaration in its Cargo manifest,\n'
                'but no standalone license or copyright notice. Author attribution above is\n'
                'preserved from that manifest; no copyright year is asserted. The following\n'
                'standard MIT terms correspond to that declaration (https://spdx.org/licenses/MIT.html).\n\n'
                + (BASE / 'mit-terms.txt').read_text())
            files_data = [(f'{name}-{version}-MIT-DECLARATION.txt', notice.encode())]
            item['noticeBasis'] = 'Exact-version upstream Cargo SPDX declaration; standard MIT terms and declared author attribution.'
        else:
            files_data = [(f'{name}-{version}-{file.name}', file.read_bytes()) for file in sorted(files)]
            if not files_data:
                raise RuntimeError(f'No dependency notice found: {name} {version}')
        for filename, data in files_data:
            output[filename] = data
            item['notices'].append(dict(file=filename, sha256=digest(data)))
        inventory.append(item)

    notices = BASE / 'notices'
    notices.mkdir(exist_ok=True)
    for file in notices.iterdir():
        if file.is_file() and file.name not in output: file.unlink()
    for filename, data in sorted(output.items()): (notices / filename).write_bytes(data)
    (BASE / 'dependencies-upstream.json').write_text(json.dumps(inventory, indent=2) + '\n')
    bundle = '\n\n'.join(f'===== {filename} =====\n' + data.decode('utf-8') for filename, data in sorted(output.items())) + '\n'
    (ROOT / 'app/src/main/assets/pdf-notices.txt').write_text(bundle)
    print(f'{len(inventory)} Android dependencies, {len(output)} notices, {len(bundle.encode())} bundled bytes')


if __name__ == '__main__': main()
