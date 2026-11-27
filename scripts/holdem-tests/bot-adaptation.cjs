const fs=require('fs'),vm=require('vm'),assert=require('assert');
const harness=fs.readFileSync(__dirname+'/regression.cjs','utf8').split('const run=')[0];
eval(harness+String.raw`
const run=s=>vm.runInContext(s,c);
let checks=0;function check(name,fn){fn();checks++;console.log('PASS',name)}
run("updateUI=()=>{};playSound=()=>{};renderLog=()=>{};updateHistoryButton=()=>{};takeAiTurn=()=>{};setup.difficulty='elite';players.forEach(p=>{p.hole=[{r:14,s:'♠'},{r:9,s:'♦'}];p.folded=false;p.stack=1000;p.roundBet=0});game.handOver=false;game.publicLines=[];game.dealerIndex=5;botReads=emptyBotReads();");

check('bots keep separate observations of the same public action',()=>{
  run("players[3].folded=true;recordBotPublicAction(2,'fold',{toCall:20});");
  assert.equal(run('botReads[1][2].folds'),1);
  assert.equal(run('botReads[3][2].folds'),.4);
  assert.equal(run('botReads[1][4].folds'),0);
  run('recordBotCompletedHand([])');
  assert.equal(run('botReads[1][2].hands'),1);
  assert.equal(run('botReads[3][2].hands'),.4);
  const saved=storage.get('poker6max_bot_reads_v1');assert.ok(saved);
  run('botReads=loadBotReads()');assert.equal(run('botReads[1][2].folds'),.995);
  const before=run('JSON.stringify(botReads)');run("tutorial.active=true;recordBotPublicAction(2,'raise',{toCall:20});recordBotCompletedHand([]);tutorial.active=false");assert.equal(run('JSON.stringify(botReads)'),before);
});

check('an AI continues wider against a loose opener than a tight opener',()=>{
  run("players[3].folded=false;game.street=0;game.preflopRaises=1;game.preflopOpenSeat=2;game.currentBet=60;game.pot=110;botReads=emptyBotReads();botReads[1][2]={...emptyBotRead(),hands:60,pfr:38,vpip:44};var oldRandom=Math.random,oldPercentile=preflopPercentile;Math.random=()=>.5;preflopPercentile=()=>.20;");
  const loose=run('preflopRangesFor(1).call');
  run('botReads[1][2].pfr=2;botReads[1][2].vpip=8');
  const tight=run('preflopRangesFor(1).call');assert.ok(loose>tight+.015,{loose,tight});
  run('preflopPercentile=()=>('+loose+'+'+tight+')/2');
  const tightMove=run('decidePreflop(1,getLegal(1)).type');
  run('botReads[1][2].pfr=38;botReads[1][2].vpip=44');
  const looseMove=run('decidePreflop(1,getLegal(1)).type');
  assert.equal(tightMove,'fold');assert.equal(looseMove,'call');
  run('Math.random=oldRandom;preflopPercentile=oldPercentile');
});

check('the same AI bluffs an overfolder more than a calling station',()=>{
  run("botReads=emptyBotReads();game.street=2;game.board=[{r:13,s:'♠'},{r:7,s:'♥'},{r:4,s:'♦'},{r:2,s:'♣'}];game.pot=100;game.currentBet=0;game.preflopAggressorSeat=null;game.lastAggressorSeat=null;game.streetHadBet=[false,false,false,false];game.streetAggressors=[null,null,null,null];players.forEach((p,i)=>{p.folded=i!==1&&i!==2;p.hole=[{r:14,s:'♣'},{r:9,s:'♥'}]});players[2].hole=[{r:12,s:'♣'},{r:8,s:'♦'}];var oldRandom2=Math.random;Math.random=()=>.045;aiEquityCache={key:equityKey(1),value:.35};botReads[1][2]={...emptyBotRead(),hands:60,faced:60,folds:5,calls:55};");
  const callMove=run('decidePostflop(1,{toCall:0,callAmount:0,canRaise:true,minTarget:20,maxTarget:500}).type');
  run('botReads[1][2].folds=55;botReads[1][2].calls=5');
  const foldMove=run('decidePostflop(1,{toCall:0,callAmount:0,canRaise:true,minTarget:20,maxTarget:500}).type');
  assert.equal(callMove,'check');assert.equal(foldMove,'raise');
  run('Math.random=oldRandom2');
});

check('the same AI finds thinner value against a calling station',()=>{
  run("var oldRandom3=Math.random;Math.random=()=>.045;aiEquityCache={key:equityKey(1),value:.51};botReads[1][2].folds=5;botReads[1][2].calls=55;");
  const stationMove=run('decidePostflop(1,{toCall:0,callAmount:0,canRaise:true,minTarget:20,maxTarget:500}).type');
  run('botReads[1][2].folds=55;botReads[1][2].calls=5');
  const folderMove=run('decidePostflop(1,{toCall:0,callAmount:0,canRaise:true,minTarget:20,maxTarget:500}).type');
  assert.equal(stationMove,'raise');assert.equal(folderMove,'check');
  run('Math.random=oldRandom3');
});

console.log(checks+' bot adaptation groups passed');
`);
