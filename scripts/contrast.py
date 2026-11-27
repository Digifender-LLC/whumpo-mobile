"""WCAG sRGB ratios, read directly from the OLED token and destination hue maps."""
import re,json
from pathlib import Path
s=Path('src/model.ts').read_text();tokens=dict(re.findall(r"(\w+):'(#[0-9A-Fa-f]{6})'",s.split('export const colors=')[1].split('};')[0]));hues={'Reviews':tokens['gold'],**dict(re.findall(r"(\w+):'(#[0-9A-Fa-f]{6})'",s.split('export const hues')[1]))}
def lum(h):
 a=[int(h[i:i+2],16)/255 for i in [1,3,5]];a=[v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4 for v in a];return sum(v*w for v,w in zip(a,[.2126,.7152,.0722]))
def ratio(a,b):
 x,y=sorted([lum(a),lum(b)]);return (y+.05)/(x+.05)
pairs=[(a,b) for a in ['ink','muted','gold'] for b in ['canvas','surface','suggestion','selected']]+[('subtitle','canvas'),('onGold','gold'),('success','successFill'),('warning','warningFill'),('error','errorFill'),('muted','errorFill'),('subtitle','selected'),('error','surface')]
rows=['| Foreground | Background | Ratio | AA normal text |','|---|---|---:|---|']
for a,b in pairs:
 r=ratio(tokens[a],tokens[b]);assert r>=4.5,(a,b,r);rows.append(f'| {a} `{tokens[a]}` | {b} `{tokens[b]}` | {r:.2f}:1 | Pass |')
rows+=['','| Destination | Icon hue | Tinted backing over Surface | Icon / backing | Label / surface |','|---|---|---|---:|---:|']
for n,h in hues.items():
 backing='#'+''.join(f'{round(int(h[i:i+2],16)*24/255+int(tokens["surface"][i:i+2],16)*(1-24/255)):02X}' for i in [1,3,5]);r=ratio(h,backing);assert r>=3,(n,r);rows.append(f'| {n} | `{h}` | `{backing}` | {r:.2f}:1 | {ratio(tokens["ink"],tokens["surface"]):.2f}:1 |')
Path('artifacts/contrast-table.md').write_text('\n'.join(rows)+'\n');Path('artifacts/tokens.json').write_text(json.dumps({'tokens':tokens,'hues':hues},indent=2));print('\n'.join(rows))
selection=[('Selected fill / canvas',ratio(tokens['selected'],tokens['canvas'])),('Selection outline / canvas',ratio(tokens['selectionRim'],tokens['canvas'])),('Selection outline / selected fill',ratio(tokens['selectionRim'],tokens['selected']))]
assert selection[1][1]>=3 and selection[2][1]>=3
table='\n| Selection geometry | Contrast |\n|---|---:|\n'+'\n'.join(f'| {name} | {value:.2f}:1 |' for name,value in selection)+'\n'
with Path('artifacts/contrast-table.md').open('a') as f:f.write(table)
print(table)

stack_rows=[('Secondary text / darkest back card',ratio(tokens['muted'],'#1A1A1A')),('Secondary text / lightest back card',ratio(tokens['muted'],'#242424'))]
assert all(r>=4.5 for _,r in stack_rows)
table='\n| Stack text | Contrast |\n|---|---:|\n'+'\n'.join(f'| {name} | {r:.2f}:1 |' for name,r in stack_rows)+'\n'
with Path('artifacts/contrast-table.md').open('a') as f:f.write(table)
print(table)

def composite(fg,bg,alpha):return '#'+''.join(f'{round(int(fg[i:i+2],16)*alpha+int(bg[i:i+2],16)*(1-alpha)):02X}' for i in [1,3,5])
pressed=ratio(composite(tokens['muted'],tokens['surface'],.85),tokens['surface'])
assert pressed>=4.5
row=f'\n| Press state | Contrast |\n|---|---:|\n| Secondary text at85% opacity / Surface | {pressed:.2f}:1 |\n'
with Path('artifacts/contrast-table.md').open('a') as f:f.write(row)
print(row)

extra=[('Avatar initials / tint',ratio('#E8C6D4','#40343B'))]
assert all(v>=4.5 for _,v in extra)
table='\n| Additional text | Contrast |\n|---|---:|\n'+'\n'.join(f'| {n} | {v:.2f}:1 |' for n,v in extra)+'\n'
with Path('artifacts/contrast-table.md').open('a') as f:f.write(table)
print(table)

keyboard=[('Composer placeholder / #2A2A2A',ratio(tokens['muted'],'#2A2A2A')),('Disabled send arrow / #303030',ratio(tokens['muted'],'#303030'))]
assert keyboard[0][1]>=4.5 and keyboard[1][1]>=3
table='\n| Keyboard control | Contrast |\n|---|---:|\n'+'\n'.join(f'| {n} | {v:.2f}:1 |' for n,v in keyboard)+'\n'
with Path('artifacts/contrast-table.md').open('a') as f:f.write(table)
print(table)
