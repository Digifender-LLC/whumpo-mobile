"""Fail if files in a frozen snapshot have changed."""
from pathlib import Path
import json,hashlib
root=Path(__file__).resolve().parents[1]
for version in ['v13','v12','v11','v10','v9','v8','v7','v6']:
 manifest=root/'artifacts'/f'{version}-snapshot-sha256.json'
 if not manifest.exists():continue
 entries=json.loads(manifest.read_text());bad=[]
 for path,digest in entries.items():
  f=root/f'{version}-snapshot'/path
  if not f.is_file() or hashlib.sha256(f.read_bytes()).hexdigest()!=digest:bad.append(path)
 assert not bad,(version,bad)
 print(version, len(entries), 'files unchanged')
