"""Measure native accessibility bounds of the compact action and header alignment."""
from device import *
import json
os.chdir(ROOT)
adb('shell','settings','put','system','font_scale','1.0');launch('today',restart=True)
ns=nodes()
def get(label):return next(n for n in ns if n.get('content-desc')==label or n.get('text')==label)
def rect(n):return list(map(int,re.findall(r'\d+',n.get('bounds'))))
def center(n):r=rect(n);return (r[1]+r[3])/2
scale=1080/411.4285714
cta=get('Review 2 decisions');status=get('Awaiting approval');r=rect(cta)
assert (r[3]-r[1])/scale>=47.8
assert abs(center(cta)-center(status))/scale<=2
controls=[get('Open Partner history'),get('Reviews, 2 approvals')];title=get('Today')
assert all(abs(center(n)-center(title))/scale<=2 for n in controls)
result={'passed':True,'compact_cta_height_dp':(r[3]-r[1])/scale,'status_cta_center_difference_dp':abs(center(cta)-center(status))/scale,'header_center_differences_dp':[abs(center(n)-center(title))/scale for n in controls]}
Path('artifacts/v11-layout-verification.json').write_text(json.dumps(result,indent=2));print(result)
