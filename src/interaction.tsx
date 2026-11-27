import {createContext,useContext,useEffect,useMemo} from 'react';
import {Pressable as NativePressable,type PressableProps,type StyleProp,type ViewStyle} from 'react-native';
import Animated,{useAnimatedStyle,useSharedValue,type SharedValue} from 'react-native-reanimated';
import {MotionContext,motion,transition} from './motion';

export const ripple={color:'rgba(255,255,255,0.16)',borderless:true,foreground:true};

type Interaction={pressCount:SharedValue<number>;hovered:SharedValue<boolean>};
export const IconInteractionContext=createContext<Interaction|null>(null);
const AnimatedPressable=Animated.createAnimatedComponent(NativePressable);

export function Pressable({onPressIn,onPressOut,onHoverIn,onHoverOut,style,disabled,android_ripple,children,...rest}:Omit<PressableProps,'style'>&{style?:StyleProp<ViewStyle>}){
 const reduced=useContext(MotionContext);
 const press=useSharedValue(0),pressCount=useSharedValue(0),hovered=useSharedValue(false);
 const interaction=useMemo(()=>({pressCount,hovered}),[pressCount,hovered]);
 const feedback=useAnimatedStyle(()=>({opacity:1-.15*press.value,transform:[{scale:reduced?1:1-.025*press.value}]}));
 useEffect(()=>{
  if(disabled){
   hovered.set(false);
   press.set(0);
  }
 },[disabled,hovered,press]);
 return <AnimatedPressable {...rest} disabled={disabled}
  android_ripple={reduced?undefined:android_ripple}
  style={[style,feedback]}
  onPressIn={event=>{pressCount.set(value=>value+1);press.set(transition(1,motion.duration.pressIn,reduced));onPressIn?.(event)}}
  onPressOut={event=>{press.set(transition(0,motion.duration.pressOut,reduced));onPressOut?.(event)}}
  onHoverIn={event=>{if(!disabled)hovered.set(true);onHoverIn?.(event)}}
  onHoverOut={event=>{hovered.set(false);onHoverOut?.(event)}}>
  {state=><IconInteractionContext.Provider value={disabled?null:interaction}>{typeof children==='function'?children(state):children}</IconInteractionContext.Provider>}
 </AnimatedPressable>;
}
