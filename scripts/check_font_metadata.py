"""Verify distinct bundled font names without a font-editing dependency."""
from pathlib import Path
import struct,json,hashlib
root=Path(__file__).resolve().parents[1];rows=[]
for f in sorted((root/'assets/fonts').glob('*.ttf')):
 d=f.read_bytes();count=struct.unpack('>H',d[4:6])[0];tables={}
 for i in range(count):
  tag,_,offset,length=struct.unpack('>4sIII',d[12+i*16:28+i*16]);tables[tag]=offset
 off=tables[b'name'];_,count,storage=struct.unpack('>HHH',d[off:off+6]);names={}
 for i in range(count):
  platform,encoding,lang,nid,length,noff=struct.unpack('>HHHHHH',d[off+6+i*12:off+18+i*12])
  if nid in (1,2,6):
   raw=d[off+storage+noff:off+storage+noff+length];names[nid]=raw.decode('utf-16-be' if platform in (0,3) else 'latin1')
 rows.append({'file':str(f.relative_to(root)),'weight':struct.unpack('>H',d[tables[b'OS/2']+4:tables[b'OS/2']+6])[0],'names':names,'sha256':hashlib.sha256(d).hexdigest()})
assert len({r['names'][6] for r in rows})==3,'iOS needs unique PostScript names'
(root/'artifacts/v10-font-metadata.json').write_text(json.dumps({'passed':True,'source':'@expo-google-fonts/inter 0.4.2; original static TTF files; OFL1.1','fonts':rows},indent=2))
print(json.dumps(rows,indent=2))
