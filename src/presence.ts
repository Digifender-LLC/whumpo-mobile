import {useCallback,useEffect,useRef,useState} from 'react';
import {runOnJS,useAnimatedStyle,useSharedValue} from 'react-native-reanimated';
import {motion,transition} from './motion';

/**
 * Keeps a conditionally shown element mounted while it animates out, so it never just vanishes.
 * Transform and opacity only (UI thread). Reduced motion changes state instantly.
 */
export function usePresence(active:boolean,reduced:boolean,distance=24){
 const [mounted,setMounted]=useState(active);
 const progress=useSharedValue(active?1:0);
 const hide=useCallback(()=>setMounted(false),[]);
 const first=useRef(true);
 if(active&&!mounted)setMounted(true);
 useEffect(()=>{
  if(first.current){first.current=false;return}
  if(reduced&&!active){progress.set(0);return}
  progress.set(transition(active?1:0,active?motion.duration.sheetIn:motion.duration.sheetOut,reduced,done=>{
   'worklet';
   if(done&&!active)runOnJS(hide)();
  }));
 },[active,reduced,progress,hide]);
 const style=useAnimatedStyle(()=>({opacity:progress.value,transform:[{translateY:reduced?0:distance*(1-progress.value)}]}));
 return {mounted:active||(mounted&&!reduced),style};
}
