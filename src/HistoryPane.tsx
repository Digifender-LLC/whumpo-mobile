import {Pressable,ripple} from './interaction';
import {useContext,useEffect,useState} from 'react';
import Animated,{FadeIn,FadeOut,useAnimatedStyle,useSharedValue} from 'react-native-reanimated';
import {MotionContext,motion,transition} from './motion';
import {View,ScrollView,TextInput,useWindowDimensions,Platform} from 'react-native';
import {useSafeAreaInsets} from 'react-native-safe-area-context';
import {Txt,IconButton,Fade,rim} from './ui';
import {AnimatedIcon} from './AnimatedIcon';
import {StateMessage} from './product-ui';
import {State,Org,organizations,history,colors as c} from './model';
export function HistoryPane({state,onClose,onSelect,onNew,onSettings,onOrg,onAddOrganization,dropdown,setDropdown,initialSearch=''}:{onAddOrganization:()=>void;initialSearch?:string;state:State;onClose:()=>void;onSelect:(t:string)=>void;onNew:()=>void;onSettings:()=>void;onOrg:(o:Org)=>void;dropdown:boolean;setDropdown:(v:boolean)=>void}){
 const inset=useSafeAreaInsets(),{fontScale}=useWindowDimensions(),[searching,setSearching]=useState(!!initialSearch),[query,setQuery]=useState(initialSearch);
 const reduced=useContext(MotionContext);
 const matches=history[state.org].map((title,i)=>({title,pinned:i<2})).filter(r=>r.title.toLowerCase().includes(query.trim().toLowerCase()));
 const top=Math.max(77,inset.top+20),closeAction=[{name:'closeHistory',label:'Close history'}];
 const [optionsHeight,setOptionsHeight]=useState(0);
 const expandedHeight=useSharedValue(0);
 useEffect(()=>{
  expandedHeight.set(transition(dropdown?optionsHeight:0,motion.duration.controls,reduced));
 },[dropdown,expandedHeight,optionsHeight,reduced]);
 const pickerStyle=useAnimatedStyle(()=>({height:expandedHeight.value}));
 return <View style={{flex:1}} onAccessibilityEscape={onClose}>

 <ScrollView showsVerticalScrollIndicator={false} keyboardShouldPersistTaps="handled" keyboardDismissMode={Platform.OS==='ios'?'interactive':'on-drag'} contentContainerStyle={{paddingTop:top,paddingHorizontal:24,paddingBottom:140}}>
 <Pressable android_ripple={ripple} accessibilityRole="button" accessibilityLabel={`Switch organization, ${state.org}`} accessibilityState={{expanded:dropdown}} onPress={()=>setDropdown(!dropdown)} onAccessibilityEscape={onClose} style={[{minHeight:56,flexDirection:'row',alignItems:'center',gap:16,marginBottom:16}]}><Txt size={26} weight="600" style={{flex:1}}>{state.org}</Txt><AnimatedIcon name="down" size={24} active={dropdown}/></Pressable>
 <Animated.View pointerEvents={dropdown?'auto':'none'} importantForAccessibility={dropdown?'auto':'no-hide-descendants'} accessibilityElementsHidden={!dropdown} style={[{overflow:'hidden'},pickerStyle]}>
  <View onLayout={e=>setOptionsHeight(e.nativeEvent.layout.height)} style={{position:'absolute',top:0,left:0,right:0,paddingBottom:24}}>
   {organizations.map(org=><Pressable android_ripple={ripple} key={org} accessibilityRole="button" accessibilityLabel={org+(state.org===org?', active':'')} accessibilityState={{selected:state.org===org}} onPress={()=>{onOrg(org);setDropdown(false)}} style={{minHeight:52,paddingVertical:10,flexDirection:'row',gap:12,alignItems:'center'}}>
    <Txt size={18} style={{flex:1}}>{org}</Txt>{state.org===org&&<AnimatedIcon name="check" color={c.gold}/>}
   </Pressable>)}
   <Pressable android_ripple={ripple} accessibilityRole="button" onPress={()=>{setDropdown(false);onAddOrganization()}} style={{minHeight:52,paddingVertical:10,flexDirection:'row',gap:12,alignItems:'center'}}>
    <AnimatedIcon name="plus" color={c.gold}/><Txt size={18} color={c.gold}>Add organization</Txt>
   </Pressable>
  </View>
 </Animated.View>
 {searching&&<Animated.View entering={reduced?undefined:FadeIn.duration(motion.duration.controls)} exiting={reduced?undefined:FadeOut.duration(motion.duration.listOut)} style={{minHeight:56,borderRadius:30,backgroundColor:c.surface,paddingHorizontal:18,marginBottom:16}}><TextInput accessibilityLabel="Search organization conversations" placeholder="Search conversations" placeholderTextColor={c.muted} value={query} onChangeText={setQuery} style={{fontFamily:'Inter400',fontSize:18,color:c.ink,minHeight:56}}/></Animated.View>}
 {!matches.length?<StateMessage title="No conversations found" detail="Try another word, or start a new conversation." action="Clear search" onPress={()=>setQuery('')}/>:<>{[true,false].map(pinned=>{const rows=matches.filter(r=>r.pinned===pinned);if(!rows.length)return null;return <View key={String(pinned)} accessibilityLabel={pinned?'Pinned conversations':'Recent conversations'} style={{marginTop:pinned?0:24}}><View accessible accessibilityRole="header" accessibilityLabel={pinned?'Pinned conversations':'Recent conversations'} style={{width:1,height:1,opacity:.01}}/>{rows.map(({title})=><Animated.View key={title} entering={searching&&!reduced?FadeIn.duration(motion.duration.listIn):undefined} exiting={searching&&!reduced?FadeOut.duration(motion.duration.listOut):undefined}><Pressable android_ripple={ripple} accessibilityRole="button" accessibilityLabel={title} accessibilityActions={closeAction} onAccessibilityAction={e=>{if(e.nativeEvent.actionName==='closeHistory')onClose()}} onAccessibilityEscape={onClose} onPress={()=>onSelect(title)} style={[{minHeight:64,paddingVertical:16,paddingRight:32,flexDirection:'row',alignItems:'center',gap:16}]}>{pinned&&<AnimatedIcon name="pin" size={20} color={c.muted}/>}<Txt size={18} style={{flex:1}}>{title}</Txt></Pressable></Animated.View>)}</View>})}</>}
 </ScrollView>
 <Pressable android_ripple={ripple} accessibilityRole="button" accessibilityLabel="Close history" onPress={onClose} style={{position:'absolute',top:top+80,bottom:120,right:0,width:24,zIndex:3}}/>
 <Fade height={100} to="#1F1F1F" style={{position:'absolute',left:0,right:0,bottom:0}}/>
 <View style={{position:'absolute',left:18,right:18,flexDirection:'row',justifyContent:'space-between',alignItems:'center',bottom:Math.max(37,inset.bottom+12)}}><Pressable android_ripple={ripple} accessibilityRole="button" accessibilityLabel={'New conversation in '+state.org} onPress={onNew} style={{minHeight:51,borderRadius:100,backgroundColor:c.gold,flexDirection:'row',alignItems:'center',justifyContent:'center',gap:8,paddingHorizontal:14,paddingVertical:10,width:fontScale>1.4?166:114,...rim}}><AnimatedIcon name="new" color="#000000"/><Txt size={17} weight="500" color="#000000">New</Txt></Pressable><View style={{width:111,minHeight:50,borderRadius:100,backgroundColor:c.surface,flexDirection:'row',alignItems:'center',justifyContent:'space-evenly',...rim}}><IconButton label={'Search '+state.org+' conversations'} icon="search" plain onPress={()=>{setSearching(v=>!v);setQuery('')}}/><IconButton label="Open settings" icon="Settings" plain onPress={onSettings}/></View></View>

 </View>;
}
