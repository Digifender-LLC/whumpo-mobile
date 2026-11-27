import {useEffect,useRef,useState} from 'react';
import {View,StyleSheet} from 'react-native';
import Animated,{FadeOutDown,useAnimatedStyle} from 'react-native-reanimated';
import {AnimatedIcon} from './AnimatedIcon';
import {Badge,Txt} from './ui';
import {Destination,colors as c} from './model';
import {Pressable,ripple} from './interaction';
import {useKeyboardMotion,compactState} from './keyboardMotion';
import {motion} from './motion';
import {TAB_LABEL_TOOLTIP_MS} from './tabLabels';

export function NavigationTabs({keyboardVisible,tabs,page,count,onSelect,introduceLabels=false}:{keyboardVisible:boolean;tabs:readonly Destination[];page:string;count:number;onSelect:(n:Destination)=>void;introduceLabels?:boolean}){
 const {progress,reduced,semanticCompact}=useKeyboardMotion();
 const hidden=keyboardVisible||semanticCompact;
 const animated=useAnimatedStyle(()=>{const m=compactState(progress.value,reduced);return {height:m.tabsSpace,opacity:m.tabsOpacity,transform:reduced?[]:[{translateY:m.tabsY}]}});
 const [tooltip,setTooltip]=useState<Destination>();
 const tooltipTimer=useRef<ReturnType<typeof setTimeout>|undefined>(undefined);
 useEffect(()=>()=>clearTimeout(tooltipTimer.current),[]);
 const showTooltip=(name:Destination)=>{
  clearTimeout(tooltipTimer.current);
  setTooltip(name);
  tooltipTimer.current=setTimeout(()=>setTooltip(undefined),TAB_LABEL_TOOLTIP_MS);
 };
 return <Animated.View testID="floating-tabs" pointerEvents={hidden?'none':'auto'} importantForAccessibility={hidden?'no-hide-descendants':'auto'} accessibilityElementsHidden={hidden} style={[{overflow:hidden?'hidden':'visible'},animated]}>
  <View style={styles.bar}>
   {tabs.map((name,i)=><Pressable key={name} android_ripple={ripple} accessibilityRole="tab" accessibilityLabel={i===2?'Pinned tab, '+name:name} accessibilityState={{selected:page===name}} onPress={()=>{setTooltip(undefined);onSelect(name)}} onLongPress={()=>showTooltip(name)} style={[styles.tab]}>
    <View style={styles.pill}>
     <View testID={page===name?'selected-tab-pill':undefined} style={[styles.pill,page===name&&styles.selected]}>
      <AnimatedIcon name={name} size={26} color={page===name?c.gold:c.ink} active={page===name}/>
     </View>
     {name==='Today'&&count>0&&<Badge count={count} style={styles.badge}/>}
    </View>
    {!hidden&&(introduceLabels||tooltip===name)&&<Animated.View testID={`tab-label-${name}`} pointerEvents="none" accessibilityElementsHidden importantForAccessibility="no-hide-descendants" exiting={reduced?undefined:FadeOutDown.duration(motion.duration.tabLabelOut)} style={styles.label}>
     <Txt size={12} weight="500" maxFontSizeMultiplier={1.2} numberOfLines={1}>{name}</Txt>
    </Animated.View>}
   </Pressable>)}
  </View>
 </Animated.View>;
}

const styles=StyleSheet.create({
 bar:{height:64,paddingTop:16,marginHorizontal:18,flexDirection:'row',gap:8},
 tab:{flex:1,minHeight:48,alignItems:'center',justifyContent:'center'},
 pill:{width:66,height:44,borderRadius:100,alignItems:'center',justifyContent:'center'},
 selected:{backgroundColor:c.selected,boxShadow:[{offsetX:0,offsetY:0,blurRadius:0,spreadDistance:1,color:c.selectionRim,inset:true}]},
 badge:{position:'absolute',top:-4,right:-2},
 label:{position:'absolute',bottom:47,maxWidth:'100%',paddingHorizontal:6,paddingVertical:2,borderRadius:10,backgroundColor:c.surface},
});
