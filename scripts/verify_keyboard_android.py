"""Native v11 compact-mode geometry, semantics, restoration and keyboard capture."""
from nav_ui import *
from PIL import Image
import json
os.chdir(ROOT)
def rect(n):return list(map(int,re.findall(r'\d+',n.get('bounds'))))
def byid(ns,ident):return next((n for n in ns if n.get('resource-id','').endswith(ident)),None)
def endpoint(opened,large=False):
 ns=nodes();tag=f'compact-mode-{int(opened)}-title-{int(not opened)}-greeting-{int(not opened)}-rear-{int(not opened)}-tabs-{int(not opened)}'
 probe=next((n for n in ns if tag in n.get('resource-id','')),None);assert probe is not None,('Missing endpoint',tag)
 vals=[n.get('text','') for n in ns];tabs=[n for n in ns if n.get('content-desc')=='More' and n.get('clickable')=='true'];greeting='Hi, Alex' in vals
 assert bool(tabs)==(not opened) and greeting==(not opened),(tabs,greeting,opened)
 org=next(n for n in ns if n.get('content-desc')=='Switch organization, Studio North');menu=next(n for n in ns if n.get('content-desc')=='Open Partner history');orgbox=rect(org);menubox=rect(menu)
 if opened:
  assert 'Today' in vals and 'Next · Website draft' not in vals
  assert abs(sum(orgbox[1::2])/2-sum(menubox[1::2])/2)<4,(orgbox,menubox)
  cta=next(n for n in ns if n.get('content-desc')=='Review 2 decisions');composer=byid(ns,'composer-pill');assert composer is not None
  gap=(rect(composer)[1]-rect(cta)[3])/(1080/411.4285714);assert gap>=15.5,('CTA/composer gap',gap)
  card=byid(ns,'approval-main-card')
  if large:
   assert card is None and byid(ns,'today-keyboard-summary') is not None
   return {'probe':probe.get('resource-id'),'org':orgbox,'menu':menubox,'action':rect(cta),'composer':rect(composer),'action_composer_gap_dp':gap}
  card_gap=(rect(composer)[1]-rect(card)[3])/(1080/411.4285714);assert card_gap>=15.5,('Card/composer gap',card_gap)
  assert not any(n.get('text')=='Awaiting approval' for n in ns),'No redundant pending chip'
  return {'probe':probe.get('resource-id'),'org':orgbox,'menu':menubox,'action':rect(cta),'composer':rect(composer),'action_composer_gap_dp':gap,'card_composer_gap_dp':card_gap}
 else:assert 'Today' in vals and 'Next · Website draft' in vals
 return {'probe':probe.get('resource-id'),'org':orgbox,'menu':menubox,'card':rect(byid(ns,'approval-main-card'))}
if __name__=='__main__':
 adb('shell','settings','put','system','font_scale','1.0');launch('today',restart=True)
 closed=endpoint(False);click('Ask your Partner, draft only',True);adb('shell','input','text','Review%sthe%sstudio');time.sleep(.6);opened=endpoint(True);shot('screenshots/android/K-01-today-keyboard-open.png')
 back();time.sleep(.5);restored=endpoint(False);assert abs((restored['card'][3]-restored['card'][1])-(closed['card'][3]-closed['card'][1]))<=3,('Card height must restore',closed,restored);shot('screenshots/android/K-02-today-keyboard-closed.png')
 click('Create draft in Partner',True);time.sleep(.5);assert find('Draft · not sent',True) is not None
 root_tab('Partner');click('Ask your Partner, draft only',True);adb('shell','input','text','Review%sthe%sservice%sdescriptions');shot('screenshots/android/K-03-partner-keyboard-open.png');back()
 open_scene('help');click('Contact support',True);click('Support request draft',True);adb('shell','input','text','Help%sreviewing%sa%sdraft');shot('screenshots/android/K-04-support-keyboard-open.png');back();back()
 settings();assert find('Help',True) is not None;shot('screenshots/android/K-06-settings-help-button.png');click('Help',True);assert find('Does approval publish my website?',True) is not None
 root_tab()
 ns=nodes();stack=byid(ns,'approval-stack');assert stack is not None
 r=rect(stack);im=Image.open(ROOT/'screenshots/android/K-02-today-keyboard-closed.png');im.crop((r[0]-6,r[1]-6,r[2]+6,r[3]+6)).save(ROOT/'screenshots/android/K-05-rear-card-closeup.png')
 adb('shell','settings','put','system','font_scale','2.0');time.sleep(1);launch('today',restart=True);click('Ask your Partner, draft only',True);adb('shell','input','text','Review%sthis');time.sleep(.5);large=endpoint(True,large=True);shot('screenshots/android/K-07-today-keyboard-large.png');back();swipe();shot('screenshots/android/K-08-rear-card-large.png');adb('shell','settings','put','system','font_scale','1.0');time.sleep(1)
 Path('artifacts/v11-keyboard-android.json').write_text(json.dumps({'passed':True,'closed':closed,'opened':opened,'restored':restored,'large':large},indent=2));print('PASS keyboard endpoints, semantics, header alignment, action bounds, Help path, native captures',flush=True)
