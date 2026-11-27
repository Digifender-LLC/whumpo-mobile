"""Targeted native regressions after moving the edge target and isolating menu touches."""
from nav_ui import *
import json
os.chdir(ROOT);results=[]
adb('shell','settings','put','system','font_scale','1.0');launch('today',restart=True)
for page in ['Today','Partner','More']:
 root_tab(page)
 adb('shell','input','swipe','35','1100','650','1100','500');time.sleep(.3)
 assert find('Switch organization, Studio North',True) is not None
 results.append(page+' edge opens history')
 click('Switch organization, Studio North',True);click('Field & Form',True)
 assert find('Field notes website',True) is not None
 click('Switch organization, Field & Form',True);click('Studio North',True)
 results.append(page+' anchored dropdown changes organization')
 adb('shell','input','swipe','850','1100','150','1100','450');time.sleep(.3)
 assert find('Weekly approvals',True) is None
 results.append(page+' left swipe closes history')
root_tab('More');n=find('Website',True);x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')));x,y=(x1+x2)//2,(y1+y2)//2
adb('shell','input','swipe',str(x),str(y),str(x),str(y),'900')
click('Pin Website to tab bar',True)
assert find('Pinned tab, Website',True) is not None
assert find('Work',True) is not None
results.append('Long press pins Website, swaps Work into More')
settings();click('Reset navigation',True);root_tab('More');assert find('Pinned tab, Work',True) is not None
results.append('Reset restores Work')
click('Open Partner history',True);back();assert find('Weekly approvals',True) is None
results.append('Back still closes history')
Path('artifacts/v9-sidebar-regressions.json').write_text(json.dumps({'passed':True,'checks':results},indent=2))
print('PASS',len(results),'targeted native sidebar/pin checks')
