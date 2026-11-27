"""The already loaded native app remains functional with its local Metro server stopped."""
from device import *
import socket,json
os.chdir(ROOT)
s=socket.socket();s.settimeout(1)
assert s.connect_ex(('127.0.0.1',8619))!=0,'Stop only this project Metro server first'
s.close()
adb('shell','dumpsys','gfxinfo',APP_ID,'reset')
for x in [165,414,165,916,916,165,414,165]:tap(x,2280)
(ROOT/'artifacts/gfxinfo-no-recording.txt').write_text(adb('shell','dumpsys','gfxinfo',APP_ID))
assert find('Needs your approval') is not None
draft='Offline review notes '+str(int(time.time()))
click('Ask your Partner, draft only',True);adb('shell','input','text',draft.replace(' ','%s'));click('Create draft in Partner',True)
for _ in range(6):
 if find(draft) is not None:break
 swipe()
assert find(draft) is not None
assert find('Draft · not sent') is not None
(ROOT/'artifacts/loaded-offline.json').write_text(json.dumps({'passed':True,'metro_port_8619_closed':True,'checks':['Today/Partner/More navigation','Existing synthetic approval card','Create local draft in Partner','Draft explicitly not sent'],'scope':'Already loaded native Whumpo session; no cold-start standalone client claimed'},indent=2))
adb('shell','rm','-f','/sdcard/whumpo-expo-v9.xml')
print('PASS: loaded session navigates and creates a draft with Metro stopped')
