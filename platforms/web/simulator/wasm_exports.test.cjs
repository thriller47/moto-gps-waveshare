"use strict";
const {test}=require("node:test");
const assert=require("node:assert/strict");
const fs=require("node:fs"),path=require("node:path");
test("replay web bridge exports every bound C function",()=>{
 const base=path.join(__dirname,"..","wasm");
 const cm=fs.readFileSync(path.join(base,"CMakeLists.txt"),"utf8");
 const cpp=fs.readFileSync(path.join(base,"replay_web.cpp"),"utf8");
 const player=fs.readFileSync(path.join(__dirname,"player.js"),"utf8");
 const suffixes=["batch_reset","batch_route","batch_sample",
  "batch_along_m","batch_lateral_m","origin_lat","origin_lon"];
 for(const suffix of suffixes){
   const symbol="moto_web_replay_"+suffix;
   assert.ok(cpp.includes(symbol),"C++ definition missing: "+symbol);
   assert.ok(cm.includes("'_"+symbol+"'"),"Emscripten export missing: "+symbol);
   assert.ok(player.includes(symbol)||player.includes('"'+suffix+'"'),
     "JS bridge binding missing: "+symbol);
 }
 assert.match(player,/const optional=\(name,returnType,args\)=>/);
});
