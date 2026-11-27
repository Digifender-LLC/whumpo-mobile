import {Pressable,ripple} from './interaction';
import {View} from 'react-native';
import {AnimatedIcon} from './AnimatedIcon';
import {Txt} from './ui';
import {colors as c,Org} from './model';

export function OrganizationChip({org,onPress,label}:{org:Org;onPress:()=>void;label?:string}){
 return <Pressable android_ripple={ripple} accessibilityRole="button" accessibilityLabel={label||`Switch organization, ${org}`} accessibilityHint="Choose the organization for your conversations and work" onPress={onPress} style={{minHeight:48,justifyContent:'center',alignSelf:'center'}}>
  {({pressed})=><View style={{minHeight:32,borderRadius:100,paddingHorizontal:12,paddingVertical:5,backgroundColor:pressed?'#414141':c.selected,flexDirection:'row',alignItems:'center',gap:6}}><Txt size={13} maxFontSizeMultiplier={1.2} weight="500">{label||org}</Txt><AnimatedIcon name="down" size={16}/></View>}
 </Pressable>;
}
export function HeaderTitle({title}:{title:string}){
 return <View style={{flex:1,paddingHorizontal:8,alignItems:'center',justifyContent:'center'}}><Txt accessibilityRole="header" numberOfLines={1} adjustsFontSizeToFit minimumFontScale={.7} size={20} maxFontSizeMultiplier={1.2} weight="600" style={{textAlign:'center'}}>{title}</Txt></View>;
}
