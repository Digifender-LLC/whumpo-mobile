"""Actual Android native-dialog isolation, Cancel and platform-back behavior."""
from nav_ui import *
import json
launch('today',restart=True);click('Reviews, 2 approvals',True);click('Website introduction',True)
checks=[]
for method in ['Cancel','Back']:
 click('Approve change',True);ns=nodes();assert not any(n.get('content-desc')=='Back' for n in ns)
 if method=='Cancel':click('Cancel',True)
 else:back()
 assert find('Awaiting approval',True) is not None
 assert find('Approve change',True) is not None
 checks.append(method+' dismisses native dialog without recording approval')
Path('artifacts/v10-native-dialog.json').write_text(json.dumps({'passed':True,'checks':checks,'underlying_controls_hidden':True},indent=2));print('PASS native modal isolation, Cancel and platform back')
