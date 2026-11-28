// Kavi 接进来的部分：对手座位接模型、模型的回答换成合规动作、出错时规则 AI 代打、复盘的牌局文字
const fs=require('fs'),assert=require('assert');
let harness=fs.readFileSync(__dirname+'/regression.cjs','utf8').split('const run=')[0];
const before='window:{matchMedia:()=>({matches:true})}';
assert(harness.includes(before));
harness=harness.replace(before,"window:{matchMedia:()=>({matches:true}),jack:{save(){},load:()=>null,models:()=>({list:[{key:'m1',label:'模型一'}],def:'m1'}),ask:(p,o)=>{asked.push([p,o]);return nextAnswer()}}}");
const asked=[];let nextAnswer=()=>Promise.resolve('{"a":"call"}');
eval(harness+String.raw`
const run=s=>vm.runInContext(s,c);
let checks=0;function check(name,fn){return Promise.resolve(fn()).then(()=>{checks++;console.log('PASS',name)})}
run("updateUI=()=>{};playSound=()=>{};renderLog=()=>{};updateHistoryButton=()=>{};tutorial.active=false;practice.active=false;var moves=[];applyAction=(i,t,x)=>{moves.push([i,t,x])};");
function setupTurn(){run("players.forEach(p=>{p.hole=[{r:14,s:'♠'},{r:13,s:'♠'}];p.folded=false;p.allIn=false;p.stack=100000;p.roundBet=0});game.handOver=false;game.street=1;game.board=[{r:2,s:'♣'},{r:7,s:'♦'},{r:9,s:'♠'}];game.pot=15000;game.currentBet=BIG_BLIND;game.minRaise=BIG_BLIND;game.actorIndex=1;game.thinking=false;game.actedSinceFullRaise=new Set();game.lastActedBet={};moves=[];");}
async function turn(){run('takeAiTurn()');await timers.pop()();for(let k=0;k<5;k++)await new Promise(r=>setImmediate(r));}
(async()=>{
await check('model answers become legal moves',()=>{
  const legal="({canFold:true,canCheck:false,toCall:200,callAmount:200,canRaise:true,minTarget:400,maxTarget:5000})";
  assert.equal(run('parseModelMove(\'{"a":"raise","to":999999,"say":"来吧"}\','+legal+').target'),5000);
  assert.equal(run('parseModelMove(\'好的 {"a":"raise","to":10} \','+legal+').target'),400);
  assert.equal(run('parseModelMove(\'{"a":"allin"}\','+legal+').target'),5000);
  assert.equal(run('parseModelMove(\'{"a":"fold"}\',{canFold:false,canCheck:true}).type'),'check');
  assert.equal(run('parseModelMove(\'{"a":"check"}\',{canFold:true,canCheck:false,toCall:200}).type'),'call');
  assert.equal(run('parseModelMove("不知道",{})'),null);
});
await check('a seat set to a model asks it with its own seat and only its own cards',async()=>{
  setupTurn();run("brains.seats={1:'m1'};players[2].hole=[{r:2,s:'♦'},{r:7,s:'♣'}];addLog('Atlas','跟注 10000');");
  nextAnswer=()=>Promise.resolve('{"a":"raise","to":40000,"say":"跟不跟？"}');
  await turn();
  assert.deepEqual(JSON.parse(run('JSON.stringify(moves)')),[[1,'raise',40000]]);
  const [prompt,opts]=asked[asked.length-1];
  assert.equal(opts.seat,'p1');assert.equal(opts.model,'m1');
  assert.match(prompt,/A♠ K♠/);assert.doesNotMatch(prompt,/2♦|7♣/);assert.match(prompt,/Atlas：跟注 10000/);
  assert.equal(run('players[1].say'),'跟不跟？');
});
await check('the next question only carries what happened since',async()=>{
  setupTurn();run("addLog('River','弃牌');");nextAnswer=()=>Promise.resolve('{"a":"call"}');
  await turn();
  const prompt=asked[asked.length-1][0];assert.match(prompt,/River：弃牌/);assert.doesNotMatch(prompt,/Atlas：跟注 10000/);
});
await check('a failing model hands the move to the rule AI',async()=>{
  setupTurn();nextAnswer=()=>Promise.reject(new Error('网络断了'));
  await turn();
  assert.equal(run('moves.length'),1);assert.ok(['fold','check','call','raise'].includes(run('moves[0][1]')));
});
await check('rule seats and training never ask a model',async()=>{
  setupTurn();run("brains.seats={};brains.banter=false;");const n=asked.length;await turn();assert.equal(asked.length,n);
  setupTurn();run("brains.seats={1:'m1'};tutorial.active=true;");await turn();assert.equal(asked.length,n);run('tutorial.active=false');
});
await check('preflop is played by the rule AI, the model only from the flop',async()=>{
  setupTurn();run("brains.seats={1:'m1'};brains.banter=false;game.street=0;game.board=[];addLog('Mika','加注到 30000');");const n=asked.length;
  await turn();assert.equal(asked.length,n);assert.equal(run('moves.length'),1);
  setupTurn();run("brains.seats={1:'m1'};");nextAnswer=()=>Promise.resolve('{"a":"check"}');await turn();
  assert.equal(asked.length,n+1);assert.match(asked[asked.length-1][0],/Mika：加注到 30000/);
});
await check('opponents talk in their own persona and hear the table chat',async()=>{
  run("brains.banter=true;brains.persona={5:'说话像海盗'};tableChat=[];lastTalkAt=0;banterBusy=false;game.pot=50000;");
  nextAnswer=()=>Promise.resolve('「哈哈，跟不跟？」');run("speak(1,'我先说一句')");run("talk(5,'你刚刚全押了。')");
  for(let k=0;k<5;k++)await new Promise(r=>setImmediate(r));
  const [prompt,opts]=asked[asked.length-1];assert.match(opts.system,/Echo/);assert.match(opts.system,/海盗/);assert.match(prompt,/Nova：我先说一句/);
  assert.equal(run('players[5].say'),'哈哈，跟不跟？');
  const n=asked.length;run("talk(4,'再说一句')");assert.equal(asked.length,n,'cooldown keeps talk from piling up');
  run("lastTalkAt=0;brains.banter=false;talk(4,'x')");assert.equal(asked.length,n);
  run("brains.persona={}");assert.match(run('personaOf(5)'),/毒舌老赌棍/);
});
await check('you can talk to the table and the named opponent answers',async()=>{
  run("brains.banter=false;tableChat=[];banterBusy=false;players.forEach(p=>{p.folded=false;p.stack=100000});");
  nextAnswer=()=>Promise.resolve('你算老几');const n=asked.length;
  run("heroSay('mika 你是不是在偷鸡')");for(let k=0;k<5;k++)await new Promise(r=>setImmediate(r));
  assert.equal(asked.length,n+1);const [prompt,opts]=asked[asked.length-1];
  assert.match(opts.system,/Mika/);assert.match(prompt,/Hero：mika 你是不是在偷鸡/);
  assert.equal(run('players[0].say'),'mika 你是不是在偷鸡');assert.equal(run('players[4].say'),'你算老几');
  run("tutorial.active=true;heroSay('喂')");assert.equal(asked.length,n+1);run('tutorial.active=false');
});
await check('table talk reaches model seats, and a model seat talks with its own cards',async()=>{
  setupTurn();run("brains.seats={1:'m1'};brains.banter=true;tableChat=[];banterBusy=false;lastTalkAt=0;");
  nextAnswer=()=>Promise.resolve('我牌可大了');run("heroSay('Nova 我这把有坚果')");for(let k=0;k<5;k++)await new Promise(r=>setImmediate(r));
  let [prompt,opts]=asked[asked.length-1];assert.equal(opts.seat,'p1');assert.equal(opts.model,'m1');assert.match(prompt,/A♠ K♠/);assert.match(opts.system,/演、在骗你/);
  run("speak(3,'我弃了我弃了')");nextAnswer=()=>Promise.resolve('{"a":"call"}');await turn();
  prompt=asked[asked.length-1][0];assert.match(prompt,/Atlas 说：「我弃了我弃了」/);assert.doesNotMatch(prompt,/Hero 说：「Nova 我这把有坚果」/,'already heard it when it answered');
});
await check('the review story tells the hand without hidden cards of folded players',()=>{
  const story=run("handStory({handNo:3,sb:5000,bb:10000,positions:['BTN'],heroHole:[{r:14,s:'♥'},{r:14,s:'♦'}],startStacks:[100,100,100,100,100,100],events:[{kind:'action',who:'你',text:'加注到 30000',analysis:{hand:'一对 A',equity:.82,toCall:0,recLabel:'加注',verdict:'正确'}},{kind:'street',who:'系统',text:'翻牌：2♣ 7♦ 9♠'}],reveals:{},result:'你赢得 45000'})");
  assert.match(story,/A♥ A♦/);assert.match(story,/教练建议 加注/);assert.match(story,/翻牌：2♣ 7♦ 9♠/);assert.match(story,/你赢得 45000/);
});
console.log(checks+' kavi seat groups passed');
})().catch(e=>{console.error(e);process.exit(1)});
`);
