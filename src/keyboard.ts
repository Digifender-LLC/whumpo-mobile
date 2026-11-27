import {useEffect,useState} from 'react';
import {Keyboard,Platform} from 'react-native';
export function useKeyboardVisible(){
 const [visible,setVisible]=useState(()=>Keyboard.isVisible());
 useEffect(()=>{
  const show=()=>setVisible(true),hide=()=>setVisible(false);
  const subs=[Keyboard.addListener('keyboardDidShow',show),Keyboard.addListener('keyboardDidHide',hide)];
  if(Platform.OS==='ios')subs.push(Keyboard.addListener('keyboardWillShow',show),Keyboard.addListener('keyboardWillHide',hide));
  return()=>subs.forEach(s=>s.remove());
 },[]);return visible;
}
