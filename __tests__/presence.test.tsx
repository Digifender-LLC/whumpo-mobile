import {renderHook,act} from '@testing-library/react-native';
import {test,expect} from '@jest/globals';
import {usePresence} from '../src/presence';
import {iconMotion} from '../src/iconMotion';

test('an element that leaves stays mounted while it animates out, and leaves instantly with Reduce Motion',async()=>{
 const animated=await renderHook(({active}:{active:boolean})=>usePresence(active,false),{initialProps:{active:true}});
 expect(animated.result.current.mounted).toBe(true);
 await act(async()=>animated.rerender({active:false}));
 expect(animated.result.current.mounted).toBe(true);
 const reduced=await renderHook(({active}:{active:boolean})=>usePresence(active,true),{initialProps:{active:true}});
 await act(async()=>reduced.rerender({active:false}));
 expect(reduced.result.current.mounted).toBe(false);
});

test('spinning icon parts turn by a whole symmetry step so the glyph lands unchanged',()=>{
 const allowed:Record<string,number>={Today:45,reset:360,plus:90,close:180,loading:360};
 for(const [name,parts] of Object.entries(iconMotion))for(const part of parts)if('spin' in part&&part.spin){
  expect(allowed[name]).toBeDefined();
  expect(Math.abs(part.rotation??0)%allowed[name]).toBe(0);
 }
});

test('no icon part erases a stroke by drawing it away (the glyph must stay readable mid-motion)',()=>{
 const drawing=Object.entries(iconMotion).filter(([,parts])=>parts.some(part=>'draw' in part&&part.draw)).map(([name])=>name);
 expect(drawing).not.toEqual(expect.arrayContaining(['check','Activity','Privacy','Help']));
});
