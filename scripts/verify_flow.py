"""End-to-end v11 checks, installed native app and the private adb server only."""
from nav_ui import *
import json
os.chdir(ROOT);log=[]
def check(*expected,absent=()):
 ns=nodes();values=[v for n in ns for v in [n.get('text',''),n.get('content-desc','')]]
 for text in expected:
  
  if not any(text in v for v in values):
   Path('artifacts/v11-flow-failure-tree.json').write_text(json.dumps([dict(n.attrib) for n in ns],indent=2));shot('artifacts/v11-flow-failure.png');raise AssertionError('Missing '+text)
  log.append('Found '+text)
 for text in absent:
  assert not any(text in v for v in values),'Unexpected '+text
  log.append('Absent '+text)
 print('PASS state:',', '.join(expected),flush=True)
def tap_switch(label):
 click(label,True);n=find(label,True);assert n is not None and n.get('checked')=='true',label+' must be On';log.append(label+' On semantics')
Path('artifacts/flow-verification.json').unlink(missing_ok=True)
adb('shell','pm','clear',APP_ID);adb('shell','settings','put','system','font_scale','1.0');launch('today',restart=True)
from verify_keyboard_android import endpoint
endpoint(False);log.append('Closed keyboard endpoint: Today/greeting/rear/tabs restored')
check('Needs your approval','Review 2 decisions',absent=['Your work'])
click('Ask your Partner, draft only',True);adb('shell','input','text','Review%sthe%sstudio%sintro');time.sleep(.5)
endpoint(True);log.append('Compact keyboard endpoint: org centered, title/greeting/rear/tabs hidden, CTA above composer')
check('Create draft in Partner',absent=['Pinned tab, Work'])
ns=nodes();assert not any(n.get('content-desc') in ['Today','Partner','More'] and n.get('clickable')=='true' for n in ns),'Keyboard must hide all tab controls'
shot('screenshots/android/P-01-typing-send-button.png')
from verify_send_raster import measure
r=measure(ROOT/'screenshots/android/P-01-typing-send-button.png','android');assert r['passed'];log.append('Actual gold send face circular40dp, equal width/height, fitted radius half diameter')
n=find('Create draft in Partner',True);bounds=list(map(int,re.findall(r'\d+',n.get('bounds'))));assert abs((bounds[2]-bounds[0])-(bounds[3]-bounds[1]))<=2;assert (bounds[2]-bounds[0])/(1080/411.4285714)>=47.8;log.append('Send touch target square48dp')
click('Switch organization, Studio North',True);check('Conversations and approvals stay with their organization.');click('Close dialog',True);endpoint(False);log.append('Compact organization chip opens the same switcher and restores the full header')
click('Ask your Partner, draft only',True);adb('shell','input','keyevent','66');check('Draft · not sent','More');log.append('Single-line hardware Return saves a local draft only');root_tab();

adb('shell','input','swipe','35','1100','650','1100','500');time.sleep(.4)
check('Website launch','Weekly approvals','Pinned conversations','Recent conversations')
ns=nodes();visible_text=[n.get('text','') for n in ns]
assert not any(t in visible_text for t in ['Organization','Partner history','Pinned','Recent','Switch organization'])
assert not any(n.get('content-desc')=='Close history' and n.get('class')=='android.widget.ImageButton' for n in ns)
log.append('History has no visual labels/X; screen-reader groups remain')
click('Switch organization, Studio North',True);check('Studio North, active','Field & Form');click('Field & Form',True);check('Field notes website',absent=['Website launch'])
click('Switch organization, Field & Form',True);click('Studio North',True)
adb('shell','input','swipe','850','1100','150','1100','450');time.sleep(.4);check('Review 2 decisions',absent=['Weekly approvals']);log.append('Swipe left closed history')
click('Open Partner history',True);back();check('Review 2 decisions',absent=['Weekly approvals']);log.append('Android back closed history')
click('Open Partner history',True);tap(1030,1500);check('Review 2 decisions',absent=['Weekly approvals']);log.append('Tap outside closed history')
click('Open Partner history',True);click('Search Studio North conversations',True);click('Search organization conversations',True);adb('shell','input','text','no-match-987');check('No conversations found');back();click('Clear search',True);check('Website launch');back()
root_tab('Partner');click('New conversation',True);check('Start something useful.','Review my website draft','Help plan the week','Summarize waiting approvals')
click('Review my website draft',True)
n=find('Create draft in Partner',True);assert n is not None and n.get('enabled')=='true';log.append('Prompt enables gold draft action')
click('Create draft in Partner',True);check('Draft · not sent','Review my website draft');shot('screenshots/android/L2-32-draft-bubble.png')
click('Add attachment',True);check('Photo or image','Document','Website draft');click('Document',True);n=find('Create draft in Partner',True);assert n is not None and n.get('enabled')=='true';log.append('Attachment menu creates a local draft token')
click('Open Partner history',True);click('Switch organization, Studio North',True);click('Field & Form',True);back();check('Field notes website',absent=['Draft · not sent']);click('Open Partner history',True);click('Switch organization, Field & Form',True);click('Studio North',True);back();check('Draft · not sent','Review my website draft')
root_tab();click('Reviews, 2 approvals',True);check('2 decisions need you','Website introduction','Service descriptions');click('Website introduction',True);check('Current text','Proposed change','Sample check')
click('Request changes',True);check('Request changes?','Request changes',absent=['Back','Approve change']);click('Request changes',True);check('Changes requested');click('Approve change',True);shot('screenshots/android/P-09-approval-sheet.png');check('Approve this change?','It will be recorded. Publishing is a separate step.');click('Approve',True);check('Approved, awaiting publish step',absent=['Approve change']);shot('screenshots/android/L2-33-approved-detail.png')
back();shot('screenshots/android/P-03-reviews-grouped.png');check('1 decision needs you','Needs your decision','Approved','Approved, awaiting publish step');back();check('Review service descriptions','Review 1 decision','Reviews, 1 approval');shot('screenshots/android/L2-34-today-one-approval.png');shot('screenshots/android/P-04-today-after-one-approval.png')
click('Review 1 decision',True);click('Service descriptions',True);click('Approve change',True);click('Approve',True);back();shot('screenshots/android/P-06-reviews-empty.png');check('Nothing needs you right now','Approved');back();check('Nothing needs you right now','View Activity',absent=['Needs your approval']);shot('screenshots/android/P-05-today-resolved.png')
open_scene('about');scroll_to('Reset sample data');click('Reset sample data',True);click('Restore sample data',True);root_tab();check('Review 2 decisions','Needs your approval')
open_scene('pinned-tab');click('Reviews',True);check('Tab preview: Today, Partner, Reviews, More');root_tab('More');check('Work',absent=['Reviews, 2 approvals']);settings();click('Reset navigation',True);root_tab('More');check('Reviews, 2 approvals',absent=['Projects','Billing','Search destinations','Options for'])
open_scene('website');check('Draft, not published','Review changes','Publish-ready checklist',absent=['Publish website']);click('Review changes',True);check('Two changes, one draft.');back();click('Publish-ready checklist',True);check('Approvals still required','Sample check','Not connected')
open_scene('activity');click('Website',True);check('Sample layout check passed',absent=['Website changes need review'])
open_scene('members');check('Owner','Admin','Member','Alex Morgan')
open_scene('account');click('Sign out',True);click('Confirm sign out',True);check('Signed out');click('Return to preview',True);check('Signed in');click('Sign-in methods',True);check('Passkey','alex@studionorth.example')
open_scene('reminders');click('Approval reminders',True);n=find('Approval reminders',True);assert n is not None and n.get('checked')=='false';log.append('Reminder preference changed without dismissing approvals');click('Quiet hours',True);check('10:00 PM','8:00 AM')
settings();click('Help',True);check('Common questions');back();check('Settings');log.append('Restored Settings Help header opens Help and back returns')
open_scene('help');click('Does approval publish my website?',True);check('Publishing is a separate step');click('Contact support',True);check('Nothing is sent to support','Support request draft');click('Support request draft',True);adb('shell','input','text','Help%swith%sa%sdraft');click('Save support draft in Partner',True);check('Draft · not sent','Support request');shot('screenshots/android/L2-35-support-draft.png')
open_scene('appearance');tap_switch('Reduce motion');click('Reduce motion',True)
open_scene('privacy');click('Export my data',True);click('Request export',True);check('Export request recorded locally')
open_scene('about');check('Preview build','Version 1.5.0','Lucide','Inter');click('Preview states',True);click('Show review error',True);check('Reviews are unavailable');click('Try again',True);time.sleep(.6);check('2 decisions need you');root_tab()
adb('shell','settings','put','global','animator_duration_scale','0');adb('shell','settings','put','global','transition_animation_scale','0');click('More',True);check('Reviews, 2 approvals');click('Today',True);check('Needs your approval');adb('shell','settings','put','global','animator_duration_scale','1');adb('shell','settings','put','global','transition_animation_scale','1')
adb('shell','settings','put','system','font_scale','2.0');time.sleep(1);launch('today',restart=True);check('Review 2 decisions');click('More',True);scroll_to('Help');check('Help');adb('shell','settings','put','system','font_scale','1.0');time.sleep(1)
Path('artifacts/flow-verification.json').write_text(json.dumps({'passed':True,'checks':log},indent=2));print('PASS',len(log),'checks',flush=True)
