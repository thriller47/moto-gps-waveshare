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
  "batch_along_m","batch_lateral_m","batch_curve_offset_m",
  "batch_curve_along_m","batch_curve_lateral_m",
  "batch_c2_offset_m","batch_c2_along_m","batch_c2_lateral_m",
  "set_predictor_mode",
  "origin_lat","origin_lon"];
 for(const suffix of suffixes){
   const symbol="moto_web_replay_"+suffix;
   assert.ok(cpp.includes(symbol),"C++ definition missing: "+symbol);
   assert.ok(cm.includes("'_"+symbol+"'"),"Emscripten export missing: "+symbol);
   assert.ok(player.includes(symbol)||player.includes('"'+suffix+'"'),
     "JS bridge binding missing: "+symbol);
 }
 assert.match(player,/const optional=\(name,returnType,args\)=>/);
});

test("simulator exposes original, OFF and curve-only predictor modes",()=>{
 const html=fs.readFileSync(path.join(__dirname,"index.html"),"utf8");
 const js=fs.readFileSync(path.join(__dirname,"player.js"),"utf8");
 const cpp=fs.readFileSync(path.join(__dirname,"..","wasm","replay_web.cpp"),"utf8");
 const candidate=fs.readFileSync(path.join(__dirname,"..","wasm","replay_curve_predictor.hpp"),"utf8");
 const c2=fs.readFileSync(path.join(__dirname,"..","wasm","replay_curve_predictor_v2.hpp"),"utf8");
 for(const id of ['value="0"','value="1"','value="2"','value="3"','id="prediction-three"'])
   assert.ok(html.includes(id),id);
 assert.match(js,/predictorMode\(mode\)/);
 assert.match(js,/batchCurveOffset/);
 assert.match(candidate,/class ReplayCurvePredictor/);
 assert.match(c2,/class ReplayCurvePredictorV2/);
 assert.match(cpp,/replay_predictor_mode == 3/);
 assert.match(cpp,/replay_predictor_mode == 2/);
});
