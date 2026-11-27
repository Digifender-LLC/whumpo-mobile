import {test,expect,jest} from '@jest/globals';
import {render,fireEvent,screen} from '@testing-library/react-native';
import {Composer} from '../src/ui';
test('empty composer cannot create a draft',async()=>{const onDraft=jest.fn();await render(<Composer value="   " onChange={()=>{}} onDraft={onDraft}/>);await fireEvent.press(screen.getByRole('button',{name:'Create draft in Partner'}));expect(onDraft).not.toHaveBeenCalled()});
test('composer labels the action as draft-only and submits once on explicit press',async()=>{const onDraft=jest.fn(),onChange=jest.fn();await render(<Composer value="Review the studio copy" onChange={onChange} onDraft={onDraft}/>);expect(screen.getByLabelText('Ask your Partner, draft only')).toBeTruthy();expect(onDraft).not.toHaveBeenCalled();await fireEvent.press(screen.getByRole('button',{name:'Create draft in Partner'}));expect(onDraft).toHaveBeenCalledTimes(1)});
