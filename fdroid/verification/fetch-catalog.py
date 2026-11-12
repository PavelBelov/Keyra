#!/usr/bin/env python3
"""Fetch real fdroiddata lint catalogs and referenced icons at a fixed revision."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import re
import sys
import urllib.request
import yaml

revision, destination, report = sys.argv[1:]
if not re.fullmatch(r'[0-9a-f]{40}', revision):
    raise ValueError('Expected an immutable fdroiddata commit SHA')
target = Path(destination)
target.mkdir(parents=True, exist_ok=True)
base = f'https://gitlab.com/fdroid/fdroiddata/-/raw/{revision}/config/'


def fetch(name):
    if Path(name).name != name:
        raise ValueError('Unexpected catalog filename')
    with urllib.request.urlopen(base + name, timeout=60) as response:
        data = response.read()
    (target / name).write_bytes(data)
    return name, hashlib.sha256(data).hexdigest()


hashes = dict(fetch(name) for name in ['categories.yml', 'antiFeatures.yml'])
icons = set()
for name in hashes:
    catalog = yaml.safe_load((target / name).read_text())
    icons.update(value['icon'] for value in catalog.values() if 'icon' in value)
with ThreadPoolExecutor(max_workers=4) as pool:
    hashes.update(pool.map(fetch, sorted(icons)))
Path(report).write_text(json.dumps({'revision': revision, 'sha256': hashes}, indent=2) + '\n')
print(f'Loaded official fdroiddata catalogs and {len(icons)} icons at {revision}')
