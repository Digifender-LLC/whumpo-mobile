import type AsyncStorage from '@react-native-async-storage/async-storage';

export const TAB_LABEL_LAUNCH_LIMIT = 3;
export const TAB_LABEL_INTRO_MS = 2800;
export const TAB_LABEL_TOOLTIP_MS = 1500;
const launchKey = 'whumpo-tab-label-launches';

export function createTabLabelLaunchTracker(storage: Pick<typeof AsyncStorage, 'getItem' | 'setItem'>) {
 let launch: Promise<boolean> | undefined;
 return () => {
  launch ??= (async () => {
   try {
    const stored = await storage.getItem(launchKey);
    const parsed = Number(stored);
    const count = Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : 0;
    if (count >= TAB_LABEL_LAUNCH_LIMIT) return false;
    await storage.setItem(launchKey, String(count + 1));
    return true;
   } catch {
    return false;
   }
  })();
  return launch;
 };
}
