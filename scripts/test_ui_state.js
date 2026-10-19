// Plain-object DOM unit tests. No browser, localhost access, or rendered page is used.
const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
class Element {
 constructor(tag='div'){this.tag=tag;this.children=[];this.dataset={};this.textContent='';this.className='';this.open=false;}
 append(...items){this.children.push(...items);}
 replaceChildren(...items){this.children=[...items];}
 addEventListener(){}
 querySelectorAll(selector){const out=[];for(const child of this.children){if(!(child instanceof Element))continue;
  if(selector==='details[open]'&&child.tag==='details'&&child.open)out.push(child);
  if(selector==='[data-cancel-order]'&&Object.hasOwn(child.dataset,'cancelOrder'))out.push(child);
  out.push(...child.querySelectorAll(selector));}return out;}
}
function load(file,exports){
 const elements=new Map(),state={now:Date.parse('2026-10-17T01:00:00Z'),fetches:0};
 const document={hidden:true,getElementById(id){if(!elements.has(id))elements.set(id,new Element());return elements.get(id);},createElement:tag=>new Element(tag),
  querySelectorAll(selector){return [...elements.values()].flatMap(x=>x.querySelectorAll(selector));},querySelector(){return {content:'unit-test-only'};},addEventListener(){}};
 class TestDate extends Date {static now(){return state.now;}}
 const context={document,Date:TestDate,Intl,URL,AbortController,setInterval(){},setTimeout(){},clearTimeout(){},window:{confirm(){return false;}},fetch(){state.fetches++;throw Error('network prohibited');}};
 vm.createContext(context);const source=fs.readFileSync(path.join(__dirname,'../src/main/resources/static/js',file),'utf8'),end=source.lastIndexOf('})();');
 vm.runInContext(source.slice(0,end)+'globalThis.ui={'+exports+'};'+source.slice(end),context);
 return {ui:context.ui,document,state,el:id=>document.getElementById(id)};
}
function portfolio(at){return {checkedAt:new Date(at).toISOString(),environment:'PAPER',issue:null,lastReconciledAt:null,positionCount:0,pendingOrderCount:1,positions:[],orders:[{id:1,name:'Fixture',side:'SELL',statusLabel:'접수',filledQuantity:0,quantity:1,limitPrice:100,createdAt:new Date(at).toISOString(),cancelable:true}]};}
test('stale market status disables cancellation and clears the old open label',()=>{
 const t=load('holdings.js','render,controls,currentResponse,setState:(p,m,s)=>{lastPortfolio=p;lastMarket=m;marketPhase=s}');
 t.ui.render(portfolio(t.state.now));t.ui.setState(t.state.now,t.state.now,'OPEN');t.ui.controls();
 let button=t.document.querySelectorAll('[data-cancel-order]')[0];assert.equal(button.disabled,false);
 t.state.now+=16000;t.ui.controls();assert.equal(button.disabled,true);assert.match(t.el('portfolio-market').textContent,/갱신 대기/);
 t.state.now+=15000;t.ui.controls();assert.match(t.el('portfolio-time').textContent,/마지막 확인/);assert.equal(t.state.fetches,0);
});
test('stale or malformed server timestamps are rejected',()=>{
 const t=load('holdings.js','currentResponse');assert.throws(()=>t.ui.currentResponse({checkedAt:'bad'},30000));
 assert.throws(()=>t.ui.currentResponse({checkedAt:new Date(t.state.now-31000).toISOString()},30000));
 assert.equal(t.ui.currentResponse({checkedAt:new Date(t.state.now).toISOString()},30000).checkedAt,new Date(t.state.now).toISOString());
});
test('unknown profit is not colored as a gain',()=>{
 const t=load('holdings.js','render');const v=portfolio(t.state.now);v.positions=[{code:'000001',name:'Fixture',quantity:1,estimatedNetPnl:null,estimatedNetReturnPercent:null}];
 t.ui.render(v);const card=t.el('portfolio-holdings').children[0];const pnl=card.children.find(x=>x.className.startsWith('holding-pnl'));
 assert.ok(pnl);assert.equal(pnl.className.includes('portfolio-positive'),false);
});
test('reading an open digest survives periodic refresh',()=>{
 const t=load('overview.js','render');const at=new Date(t.state.now).toISOString();
 const v={checkedAt:at,session:{label:'주말 휴장'},databaseHealthy:true,financialCompanies:1,financialCurrent:true,reviews:{activeCount:0,completedCount:0,unreadCount:0},budget:{remainingUsd:10},information:{sources:[]},digests:[{id:1,report:{slot:'MORNING',generatedAt:at,scheduledAt:at,researchAt:at,researchCurrent:true,companies:[],articles:[],notes:[]}}],nextSteps:[]};
 t.ui.render(v);t.el('digests').children[0].open=true;t.ui.render(v);assert.equal(t.el('digests').children[0].open,true);assert.equal(t.state.fetches,0);
});
