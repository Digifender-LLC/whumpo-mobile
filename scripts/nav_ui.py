"""Visible Android UI navigation; no custom intents that reopen the dev launcher."""
from device import *
def root_tab(name='Today'):
 for _ in range(10):
  if any(n.get('content-desc')=='Today' and n.get('clickable')=='true' for n in nodes()):
   click(name,True);return
  back()
 raise AssertionError('No root tabs')
def scroll_to(text,attempts=10):
 for _ in range(attempts):
  ns=nodes();matches=[n for n in ns if n.get('text')==text or n.get('content-desc')==text];matches.sort(key=lambda n:n.get('clickable')!='true')
  viewports=[list(map(int,re.findall(r'\d+',n.get('bounds')))) for n in ns if n.get('class','').endswith('ScrollView')]
  v=max(viewports,key=lambda r:(r[2]-r[0])*(r[3]-r[1])) if viewports else [0,200,1080,2340]
  if matches:
   r=list(map(int,re.findall(r'\d+',matches[0].get('bounds'))))
   if r[3]>r[1] and r[1]>=v[1] and r[3]<=v[3]-44:return
  swipe()
 raise AssertionError('Unreachable '+text)
def more():root_tab('More')
def settings():more();click('Open settings',True)
def reviews():root_tab();click('Reviews, 2 approvals',True)
def approval():reviews();click('Website introduction',True)
def website():more();click('Website',True)
def open_scene(scene):
 if scene=='reviews':reviews()
 elif scene=='approval-detail':approval()
 elif scene=='website':website()
 elif scene=='activity':more();click('Activity',True)
 elif scene=='organization':more();click('Organization',True)
 elif scene=='account':more();click('Account',True)
 elif scene=='members':more();click('Organization',True);click('Members & permissions',True)
 elif scene=='pinned-tab':settings();click('Pinned tab',True)
 elif scene=='reminders':settings();scroll_to('Approval reminders');click('Approval reminders',True)
 elif scene=='privacy':settings();scroll_to('Privacy & data');click('Privacy & data',True)
 elif scene=='help':more();scroll_to('Help');click('Help',True)
 elif scene=='partner-composer':root_tab('Partner');click('Ask your Partner, draft only',True);adb('shell','input','text','Review%sthe%sstudio%sintroduction')
 elif scene=='org-dropdown':root_tab();click('Open Partner history',True);click('Switch organization, Studio North',True)
 elif scene=='approval-confirmation':approval();scroll_to('Approve change');click('Approve change',True)
 elif scene=='about':settings();scroll_to('About');click('About',True)
 elif scene=='appearance':settings();scroll_to('Appearance');click('Appearance',True)
 elif scene=='checklist':website();click('Publish-ready checklist',True)
 elif scene=='review-changes':website();click('Review changes',True)
 elif scene=='quiet-hours':open_scene('reminders');click('Quiet hours',True)
 elif scene=='sign-in-methods':open_scene('account');click('Sign-in methods',True)
 elif scene=='work':root_tab('Pinned tab, Work')
 elif scene=='project':open_scene('work');click('Launch plan',True)
 elif scene=='partner-new':root_tab('Partner');click('New conversation',True)
 else:raise ValueError(scene)
 time.sleep(.4)
SCENES=[('reviews','reviews'),('approval-detail','approval-detail'),('website','website'),('activity','activity'),('organization','organization'),('account','account'),('members','members'),('pinned-tab','pinned-tab'),('reminders','approval-reminders'),('privacy','privacy'),('help','help'),('partner-composer','partner-composer'),('org-dropdown','org-dropdown-in-sidebar'),('approval-confirmation','approval-confirmation'),('about','about'),('appearance','appearance'),('checklist','checklist'),('review-changes','review-changes'),('quiet-hours','quiet-hours'),('sign-in-methods','sign-in-methods'),('work','work'),('project','project'),('partner-new','partner-empty'),('attachments','attachments'),('signout-confirmation','signout-confirmation'),('export-confirmation','export-confirmation'),('contact-support','contact-support'),('history-empty','history-empty'),('review-error','review-error'),('review-loading','review-loading'),('preview-states','preview-states')]

def warm_link(scene):
 adb('shell','am','start','-n',APP_ID+'/.MainActivity','-a','android.intent.action.VIEW','-d','whumpoexpo://'+scene)
 time.sleep(.9)
 expected={'checklist':'Publish-ready checklist','members':'Members & permissions','pinned-tab':'Pinned tab','reminders':'Approval reminders','privacy':'Privacy & data','sign-in-methods':'Sign-in methods','partner-new':'Start something useful.','org-dropdown':'Field & Form','approval-confirmation':'Approve','attachments':'Add attachment','signout-confirmation':'Confirm sign out','export-confirmation':'Request export','contact-support':'Draft a support request','history-empty':'No conversations found','review-error':'Reviews are unavailable','review-loading':'Loading your reviews','today-review':'Review 2 decisions'}.get(scene,scene.replace('-', ' ').capitalize())
 ns=nodes();assert any(n.get('text')==expected or n.get('content-desc')==expected for n in ns),('Wrong native destination',scene,expected)
 assert 'com.whumpo.expomock' in adb('shell','dumpsys','activity','activities'), 'Native Whumpo must remain active'
