"""Real first approval at200% text, including its decision sheet and both groups."""
from nav_ui import *
adb('shell','pm','clear',APP_ID);adb('shell','settings','put','system','font_scale','2.0');launch('today',restart=True)
click('Reviews, 2 approvals',True);click('Website introduction',True);click('Approve change',True);shot('screenshots/android/A-12-approval-sheet-large.png');click('Approve',True);time.sleep(3);back()
assert find('Needs your decision',True) is not None
shot('screenshots/android/A-11-reviews-grouped-large.png');swipe();assert find('Approved',True) is not None;shot('screenshots/android/A-13-reviews-approved-large.png')
adb('shell','settings','put','system','font_scale','1.0');launch('today',restart=True)
print('PASS real grouped Reviews and confirmation at200%')
