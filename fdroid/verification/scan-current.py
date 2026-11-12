#!/usr/bin/env python3
"""Source-only preflight of committed local HEAD using a temporary recipe copy."""
from pathlib import Path
import subprocess
import sys
import yaml

source, recipe_file, config_file, workspace = map(Path, sys.argv[1:])
properties = dict(line.split('=', 1) for line in (source / 'app-info.properties').read_text().splitlines()
                  if line and not line.startswith('#') and '=' in line)
recipe = yaml.safe_load(recipe_file.read_text())
build = recipe['Builds'][-1]
build['commit'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=source, text=True).strip()
build['versionName'] = properties['application.version.name']
build['versionCode'] = int(properties['application.version.code'])
recipe['Builds'] = [build]
# Only this source-only sandbox uses the local public bundle; submission metadata
# and the release build keep the real HTTPS repository and published reference.
recipe['Repo'] = '/input/keyra.bundle'
identifier = properties['application.id']
(workspace / 'metadata').mkdir(parents=True, exist_ok=True)
(workspace / f'metadata/{identifier}.yml').write_text(yaml.safe_dump(recipe, sort_keys=False))
(workspace / 'config.yml').write_text(config_file.read_text())
result = subprocess.run(['fdroid', 'scanner', '--exit-code', f'{identifier}:{build["versionCode"]}'], cwd=workspace)
sys.exit(result.returncode)
