import {test,expect,jest} from '@jest/globals';
import {render,fireEvent,screen} from '@testing-library/react-native';
import {More} from '../src/screens';
jest.mock('react-native-safe-area-context',()=>({useSafeAreaInsets:()=>({top:0,right:0,bottom:0,left:0})}));
test('More is four tiles plus one neutral account/settings/help group with no search or overflow',async()=>{
 const pin=jest.fn();const r=await render(<More pin="Work" onOpen={()=>{}} onPin={pin}/>);
 for(const name of ['Reviews, 2 approvals','Website','Activity','Organization','Account','Settings','Help'])expect(screen.getByRole('button',{name})).toBeTruthy();
 for(const name of ['Work','Partner','Today','Projects','Billing','Options for Reviews'])expect(screen.queryByRole('button',{name})).toBeNull();
 expect(screen.queryByRole('search')).toBeNull();
 await fireEvent(screen.getByRole('button',{name:'Website'}),'accessibilityAction',{nativeEvent:{actionName:'pin'}});expect(pin).toHaveBeenCalledWith('Website');
 expect(screen.getByRole('button',{name:'Organization'}).props.accessibilityActions).toBeUndefined();
 await r.rerender(<More pin="Website" onOpen={()=>{}} onPin={pin}/>);expect(screen.getByRole('button',{name:'Work'})).toBeTruthy();expect(screen.queryByRole('button',{name:'Website'})).toBeNull();
});
