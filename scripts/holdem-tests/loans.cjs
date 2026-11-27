const fs=require('fs'),vm=require('vm'),assert=require('assert');
const harness=fs.readFileSync(__dirname+'/regression.cjs','utf8').split('const run=')[0];
eval(harness+String.raw`
const run=s=>vm.runInContext(s,c);
let checks=0;function check(name,fn){fn();checks++;console.log('PASS',name)}
run("updateUI=()=>{};playSound=()=>{};renderLog=()=>{};updateHistoryButton=()=>{};takeAiTurn=()=>{};initLobby=()=>{};tutorial.active=false;practice.active=false;setup={mode:'cash',room:'novice',difficulty:'casual',stack:1000000,sb:5000,bb:10000};game.mode='cash';");

check('old aggregate debt migrates into a legacy loan entry',()=>{
  run("localStorage.setItem(ECONOMY_KEY,JSON.stringify({wallet:440000,debt:875000,loans:2,handsWithDebt:8}));var legacyEconomy=loadEconomy();");
  assert.equal(run('legacyEconomy.wallet'),440000);assert.equal(run('legacyEconomy.debt'),875000);
  assert.equal(run('legacyEconomy.loanLedger.length'),1);assert.equal(run('legacyEconomy.loanLedger[0].type'),'legacy');assert.equal(run('legacyEconomy.loanLedger[0].balance'),875000);
});

check('saves from the version with shops keep their debt but drop shops, stocks and the club',()=>{
  run("localStorage.setItem(ECONOMY_KEY,JSON.stringify({wallet:500000,debt:200000,loans:1,businesses:{supermarket:1},lifeEarned:3000,stockMarket:{tick:4},paradise:{opened:true},loanLedger:[{id:'a',type:'asset',assetId:'supermarket',principal:200000,balance:200000}]}));var oldEconomy=loadEconomy();");
  assert.equal(run('oldEconomy.debt'),200000);assert.equal(run('oldEconomy.loanLedger[0].type'),'legacy');
  assert.equal(run("'businesses' in oldEconomy||'stockMarket' in oldEconomy||'paradise' in oldEconomy||'lifeEarned' in oldEconomy"),false);
  assert.equal(run('(economy=oldEconomy,lifeNetWorth())'),300000);
});

check('legacy debts accrue the existing 1k fee and repay in FIFO order',()=>{
  run("economy={...ECONOMY_DEFAULT,wallet:0,debt:50000,loanLedger:[]};chargeDebtHandFee();");
  assert.equal(run('economy.debt'),51000);assert.equal(run('economy.loanLedger[0].type'),'legacy');assert.equal(run('economy.loanLedger[0].balance'),51000);
  run("economy.wallet=100000;repayDebt(51000)");assert.equal(run('economy.debt'),0);assert.equal(run('economy.wallet'),49000);
});

check('bankruptcy loans remain available as separate ledger entries',()=>{
  run("economy={...ECONOMY_DEFAULT,wallet:100000,debt:0,loanLedger:[]};applyBankruptcyLoan();");
  assert.equal(run('economy.wallet'),1100000);assert.equal(run('economy.debt'),1000000);assert.equal(run("economy.loanLedger[0].type"),'bankruptcy');
});

check('loans survive an active-hand checkpoint',()=>{
  run("economy={...ECONOMY_DEFAULT,wallet:100000,debt:0,loanLedger:[]};applyBankruptcyLoan();game.handNo=2;game.handOver=true;checkpoint();var beforeLoanRestore=JSON.stringify([economy.wallet,economy.debt,economy.loanLedger]);economy={...ECONOMY_DEFAULT,wallet:0};restoreCheckpoint();");
  assert.equal(run('JSON.stringify([economy.wallet,economy.debt,economy.loanLedger])'),run('beforeLoanRestore'));
});

check('older active-hand saves with only aggregate debt migrate safely',()=>{
  run("economy={...ECONOMY_DEFAULT,wallet:250000};game.handNo=2;game.handOver=true;checkpoint();");
  const snapshot=JSON.parse(storage.get('poker6max_active_v1'));delete snapshot.economy.loanLedger;snapshot.economy.debt=875000;storage.set('poker6max_active_v1',JSON.stringify(snapshot));
  run("economy={...ECONOMY_DEFAULT,wallet:0};restoreCheckpoint();");
  assert.equal(run('economy.debt'),875000);assert.equal(run('economy.loanLedger[0].type'),'legacy');assert.equal(run('economy.loanLedger[0].balance'),875000);
});

console.log(checks+' loan groups passed');
`);
