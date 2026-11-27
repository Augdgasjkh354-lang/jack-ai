// 在 Kavi 里：存档走 jack.save（进 Kavi 的备份）；第一次打开时把网页存储里已有的德州存档搬过来
const fs=require('fs'),assert=require('assert');
let harness=fs.readFileSync(__dirname+'/regression.cjs','utf8').split('const run=')[0];
const before='window:{matchMedia:()=>({matches:true})}';
assert(harness.includes(before));
harness=harness.replace(before,"window:{matchMedia:()=>({matches:true}),jack:{save:o=>{saved=JSON.parse(JSON.stringify(o))},load:()=>saved}}");
const lsMock='localStorage:{getItem:k=>storage.get(k)||null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)}';
assert(harness.includes(lsMock));
harness=harness.replace(lsMock,'localStorage:{getItem:k=>storage.get(k)||null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k),get length(){return storage.size},key:n=>[...storage.keys()][n]}');
harness=harness.replace('const nodes=new Map(),storage=new Map()',"const nodes=new Map(),storage=new Map([['poker6max_economy_v1',JSON.stringify({wallet:777000})],['other_app','x']])");
let saved=null;
eval(harness+String.raw`
const run=s=>vm.runInContext(s,c);
assert.equal(run('economy.wallet'),777000);
run('economy.wallet=123000;saveEconomy()');
assert.equal(saved,null);timers.pop()();
assert.equal(JSON.parse(saved.poker.poker6max_economy_v1).wallet,123000);
assert.equal('other_app' in saved.poker,false);
assert.equal(JSON.parse(storage.get('poker6max_economy_v1')).wallet,777000);
console.log('PASS storage goes through jack.save and migrates old web storage');
`);
