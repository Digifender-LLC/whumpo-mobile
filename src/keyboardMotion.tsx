import {createContext,useContext,useEffect,useState} from 'react';
import {Keyboard,Platform,View} from 'react-native';
import {useReanimatedKeyboardAnimation} from 'react-native-keyboard-controller';
import {SharedValue,useAnimatedReaction,useDerivedValue,runOnJS} from 'react-native-reanimated';
export function phase(p:number,start:number,end:number){'worklet';return Math.max(0,Math.min(1,(p-start)/(end-start)))}
/** One continuous native progress drives all geometry; no delayed independent animations. */
export function compactState(p:number,reduced=false){
 'worklet';p=reduced?(p>=.5?1:0):Math.max(0,Math.min(1,p));
 const rear=reduced?p:phase(p,0,.48),header=reduced?p:phase(p,.22,.88),greeting=reduced?p:phase(p,.30,1),tabs=reduced?p:phase(p,.46,1);
 const layout=reduced?(p>=.5?1:0):greeting;
 return {rearOpacity:reduced?Math.max(0,1-p*2):1-rear,rearY:reduced?0:-76*rear,rearSpace:reduced?(p>=.5?0:1):1-rear,titleOpacity:reduced?Math.max(0,1-p*2):1-phase(p,.12,.48),chipY:reduced?0:48*(1-header),chipTop:reduced?(p>=.5?0:48):0,chipOpacity:reduced?Math.abs(1-2*p):1,greetingOpacity:reduced?Math.max(0,1-p*2):1-greeting,greetingSpace:1-layout,headerSpace:reduced?(p>=.5?0:48):48*(1-header),tabsOpacity:reduced?Math.max(0,1-p*2):1-tabs,tabsY:reduced?0:20*tabs,tabsSpace:reduced?(p>=.5?0:64):64*(1-tabs),layout,contentOpacity:reduced?Math.abs(1-2*p):1};
}
const zero={value:0} as SharedValue<number>;
export type KeyboardMotion={progress:SharedValue<number>;height:SharedValue<number>;compact:boolean;semanticCompact:boolean;settled:number;reduced:boolean};
export const KeyboardMotionContext=createContext<KeyboardMotion>({progress:zero,height:zero,compact:false,semanticCompact:false,settled:0,reduced:false});
export const useKeyboardMotion=()=>useContext(KeyboardMotionContext);
export function useCoordinatedKeyboard(reduced:boolean):KeyboardMotion{
 const native=useReanimatedKeyboardAnimation(),[compact,setCompact]=useState(false),[settled,setSettled]=useState(0);
 // Keyboard events are a semantic fallback; UI-thread native progress remains the geometry source.
 useEffect(()=>{const show=()=>setCompact(true),hide=()=>{setCompact(false);if(Platform.OS==='android')Keyboard.dismiss()};const subscriptions=[Keyboard.addListener('keyboardDidShow',show),Keyboard.addListener('keyboardDidHide',hide)];if(Platform.OS==='ios')subscriptions.push(Keyboard.addListener('keyboardWillShow',show),Keyboard.addListener('keyboardWillHide',hide));return()=>subscriptions.forEach(s=>s.remove())},[]);
 useAnimatedReaction(()=>native.progress.value>0.001,(value,old)=>{if(value!==old)runOnJS(setCompact)(value)});
 const progress=useDerivedValue(()=>reduced?(compact?1:0):native.progress.value);
 useAnimatedReaction(()=>progress.value,(p)=>{if(p<=.001)runOnJS(setSettled)(0);else if(p>=.999)runOnJS(setSettled)(1)});
 return {progress,height:native.height,compact,semanticCompact:compact||settled===1,settled,reduced};
}
export function CompactProbe(){const {settled,reduced}=useKeyboardMotion();return <View testID={`compact-mode-${settled}-title-${1-settled}-greeting-${1-settled}-rear-${1-settled}-tabs-${1-settled}-reduced-${reduced?1:0}`} collapsable={false} pointerEvents="none" importantForAccessibility="no" accessibilityElementsHidden style={{position:'absolute',top:0,left:0,width:1,height:1}}/>}
