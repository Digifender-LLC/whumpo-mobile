"""Actual native UI states for all v10 P0 regressions; private adb only."""
from nav_ui import *
from verify_send_raster import measure
import json,shutil,sys
os.chdir(ROOT);number=sys.argv[1]
adb('shell','pm','clear',APP_ID);adb('shell','settings','put','system','font_scale','1.0');launch('today',restart=True)
click('Ask your Partner, draft only',True);adb('shell','input','text','Review%sthe%sstudio%sintro');time.sleep(.6);shot('screenshots/android/P-01-typing-send-button.png')
assert not any(n.get('clickable')=='true' and n.get('content-desc')=='More' for n in nodes())
field=find('Ask your Partner, draft only',True);target=find('Create draft in Partner',True)
f=list(map(int,re.findall(r'\d+',field.get('bounds'))));t=list(map(int,re.findall(r'\d+',target.get('bounds'))));assert abs((f[1]+f[3]-t[1]-t[3])/2)/(1080/411.4285714)<1
result=measure(ROOT/'screenshots/android/P-01-typing-send-button.png','android');(ROOT/'artifacts/v10-send-raster-android.json').write_text(json.dumps(result,indent=2))
click('Create draft in Partner',True);click('Ask your Partner, draft only',True);adb('shell','input','text','Review%sthe%sservice%sdescriptions');shot('screenshots/android/P-02-partner-typing.png')
for text in ['Keep it clear.','Use short sentences.','Keep the voice warm.','Check the details.','Save as a draft.']:
 adb('shell','input','keyevent','66');adb('shell','input','text',text.replace(' ','%s'))
shot('screenshots/android/P-10-multiline-composer.png');click('Create draft in Partner',True)
root_tab();click('Reviews, 2 approvals',True);click('Website introduction',True);click('Approve change',True);shot('screenshots/android/P-09-approval-sheet.png');click('Approve',True);time.sleep(3);back();shot('screenshots/android/P-03-reviews-grouped.png');back();assert find('Review service descriptions',True) is not None;shot('screenshots/android/P-04-today-after-one-approval.png')
click('Review 1 decision',True);click('Service descriptions',True);click('Approve change',True);click('Approve',True);time.sleep(3);back();shot('screenshots/android/P-06-reviews-empty.png');back();assert find('Nothing needs you right now',True) is not None;shot('screenshots/android/P-05-today-resolved.png')
warm_link('account');shot('screenshots/android/P-07-signout-row.png');warm_link('settings');shot('screenshots/android/P-08-settings-with-account.png')
folder=ROOT/'compare'/('v10-pass'+number)/'android';folder.mkdir(parents=True,exist_ok=True)
for f in (ROOT/'screenshots/android').glob('P-*.png'):shutil.copy2(f,folder/f.name)
print('PASS actual v10 Android polish states',flush=True)
