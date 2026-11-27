export const destinations = ['Today','More','Reviews','Work','Website','Activity','Partner','Organization','Account','Settings','Help'] as const;
export type Destination = typeof destinations[number];
export const pinnableDestinations = ['Work','Reviews','Website','Activity'] as const;
export type Pinnable = typeof pinnableDestinations[number];
export const isPinnable=(name:unknown):name is Pinnable=>pinnableDestinations.some(d=>d===name);
export const moreDestinations=(pin:Destination):Destination[]=>['Reviews','Work','Website','Activity','Organization'].filter(d=>d!==pin) as Destination[];
export type Org = 'Studio North' | 'Field & Form';
export const organizations:Org[]=['Studio North','Field & Form'];
export const history:Record<Org,string[]>={
 'Studio North':['Website launch','Weekly approvals','Services page copy','Spring project plan','A calmer booking flow','Notes for next week','Team handover','Launch checklist','Ideas for the studio'],
 'Field & Form':['Field notes website','Wholesale approvals','Distributor questions','Autumn project plan','Product descriptions','Regional launch','Team review','Next collection'],
};
export const approvals=[
 {id:'intro',title:'Website introduction',summary:'A clearer introduction to the studio.',before:'We create thoughtful spaces for everyday life.',after:'Thoughtful spaces, designed around the way you live.',evidence:'Copy check · introduction-014'},
 {id:'services',title:'Service descriptions',summary:'More specific descriptions for three services.',before:'Design support for your next project.',after:'Space planning, interior design and a practical launch plan.',evidence:'Layout check · services-015'},
] as const;
export type ApprovalId=typeof approvals[number]['id'];
export type ApprovalStatus='pending'|'approved'|'changes';
const pending=():Record<ApprovalId,ApprovalStatus>=>({intro:'pending',services:'pending'});
export type State={org:Org;pins:Record<Org,Destination>;drafts:Record<Org,Record<string,string>>;conversations:Record<Org,string>;approvals:Record<Org,Record<ApprovalId,ApprovalStatus>>;reduceMotion:boolean;reminders:boolean;digest:boolean;quietHours:boolean;exportRequested:boolean;signedIn:boolean;offline:boolean};
export const initialState:State={org:'Studio North',pins:{'Studio North':'Work','Field & Form':'Work'},drafts:{'Studio North':{},'Field & Form':{}},conversations:{'Studio North':'Website launch','Field & Form':'Field notes website'},approvals:{'Studio North':pending(),'Field & Form':pending()},reduceMotion:false,reminders:true,digest:false,quietHours:true,exportRequested:false,signedIn:true,offline:false};
export type Action={type:'org';org:Org}|{type:'pin';destination:Destination}|{type:'reset'}|{type:'resetData'}|{type:'draft';text:string}|{type:'conversation';title:string}|{type:'approve';id:ApprovalId}|{type:'changes';id:ApprovalId}|{type:'motion'}|{type:'reminders'}|{type:'digest'}|{type:'quiet'}|{type:'export'}|{type:'signout'}|{type:'signin'}|{type:'offline'}|{type:'hydrate';org:Org;pins:State['pins']};
export const pendingApprovals=(s:State)=>approvals.filter(a=>s.approvals[s.org][a.id]!=='approved');
export const approvedApprovals=(s:State)=>approvals.filter(a=>s.approvals[s.org][a.id]==='approved');
export const pendingCount=(s:State)=>pendingApprovals(s).length;
export const todayApprovalCopy=(s:State)=>{const items=pendingApprovals(s);return {count:items.length,title:items.length===1?(items[0].id==='services'?'Review service descriptions':'Review website introduction'):'Review website changes',body:items.length===1?items[0].summary:'2 proposed changes. Nothing is published.'}};
export type ChipVariant='action'|'neutral'|'verified'|'destructive';
export const chipVariant=(label:string):ChipVariant=>label==='Sample check'?'verified':/awaiting approval|changes requested|approvals? still required/i.test(label)?'action':/error|unavailable|delete/i.test(label)?'destructive':'neutral';
export const currentDraft=(s:State)=>s.drafts[s.org][s.conversations[s.org]]||'';
export function reducer(s:State,a:Action):State{
 switch(a.type){
 case 'org':return {...s,org:a.org};
 case 'pin':return isPinnable(a.destination)?{...s,pins:{...s.pins,[s.org]:a.destination}}:s;
 case 'reset':return {...s,pins:{...s.pins,[s.org]:'Work'}};
 case 'resetData':return {...s,drafts:initialState.drafts,approvals:{'Studio North':pending(),'Field & Form':pending()},conversations:initialState.conversations,exportRequested:false,signedIn:true};
 case 'draft':return a.text.trim()?{...s,drafts:{...s.drafts,[s.org]:{...s.drafts[s.org],[s.conversations[s.org]]:a.text.trim()}}}:s;
 case 'conversation':return {...s,conversations:{...s.conversations,[s.org]:a.title}};
 case 'approve':return {...s,approvals:{...s.approvals,[s.org]:{...s.approvals[s.org],[a.id]:'approved'}}};
 case 'changes':return s.approvals[s.org][a.id]==='approved'?s:{...s,approvals:{...s.approvals,[s.org]:{...s.approvals[s.org],[a.id]:'changes'}}};
 case 'motion':return {...s,reduceMotion:!s.reduceMotion};
 case 'reminders':return {...s,reminders:!s.reminders};
 case 'digest':return {...s,digest:!s.digest};
 case 'quiet':return {...s,quietHours:!s.quietHours};
 case 'export':return {...s,exportRequested:true};
 case 'signout':return {...s,signedIn:false};
 case 'signin':return {...s,signedIn:true};
 case 'offline':return {...s,offline:!s.offline};
 case 'hydrate':return {...s,org:a.org,pins:Object.fromEntries(organizations.map(o=>[o,isPinnable(a.pins[o])?a.pins[o]:'Work'])) as State['pins']};
 }
}
export const todayItems=()=>[{id:'approvals',title:'Needs your approval',required:true}];
export const colors={canvas:'#000000',surface:'#303030',suggestion:'#212121',selected:'#2A2A2A',selectionRim:'#737373',ink:'#FFFFFF',muted:'#B0B0B0',subtitle:'#CECECE',hairline:'#4F4F4F',gold:'#E0BE78',onGold:'#000000',success:'#B0DDB9',successFill:'#20362A',warning:'#FFD29D',warningFill:'#3C2B1D',error:'#FFB4AB',errorFill:'#40221F'};
export const hues:Record<string,string>={Reviews:colors.gold,Work:'#8DAEE0',Website:'#87BBCB',Activity:'#D5A08B',Partner:'#D5A6B9',Organization:'#9CB8D8',Account:'#D9B0BF',Settings:'#B4A8D0',Help:'#D8A98D'};
export const spacing={xs:4,sm:8,md:12,lg:16,xl:24,xxl:32};
export const typeScale={badge:12,caption:13,compact:14,secondary:15,body:17,row:18,title:20,greeting:24,organization:26,display:28};
