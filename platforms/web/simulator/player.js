/* Moto Chat GPS: replay degli snapshot Android sul renderer reale LVGL (WASM).
 * Senza Emscripten mostra un'ANTEPRIMA GEOMETRICA 2D, chiaramente distinta.
 * I file caricati restano nel browser: nessun upload o salvataggio automatico.
 */
(() => {
  "use strict";
  const $ = id => document.getElementById(id);
  const names = ["trace-file","sample","snapshot","status","play","previous","next",
    "prev-event","next-event","speed","timeline","time","frame","maneuver",
    "roads","zoom","speed-kph","performance","lvgl-performance","events","profile","canvas","preview","summary","curve-zoom"];
  const ui = Object.fromEntries(names.map(id=>[id,$(id)]));
  const required = names.filter(id=>!ui[id]);
  if (required.length) throw Error("Controlli simulator mancanti: " + required.join(", "));
  const model = window.MotoReplayModel, fallback = window.MotoFallbackMap;
  if (!model || !fallback) throw Error("Moduli replay non caricati");
  const manoeuvres = {unknown:0,continue:1,keep_left:2,turn_left:3,
    sharp_left:4,u_turn:5,keep_right:6,turn_right:7,sharp_right:8,
    roundabout:10,exit:11,arrive:12};
  const labels = ["—","Dritto","Tieni sinistra","Sinistra","Sinistra stretta",
    "Inversione","Tieni destra","Destra","Destra stretta","Inversione",
    "Rotatoria","Uscita","Arrivo"];
  const states = {idle:0,acquiring:1,planning:2,navigating:3,rerouting:4,arrived:5};
  const networks = {offline:0,connecting:1,online:2};
  const numeric = model.finite;
  let api = null, dataset = null, frames = [], events = [];
  let index = 0, rendered = -1, appliedRoute = null, appliedScene = null;
  let playing = false, base = 0, started = 0;
  let lastProfilePaint = -Infinity, lastActiveEventFrame = null;
  let profileSpeeds = [], profileMaxSpeed = 40;
  // Separate JS-to-WASM update cost from browser animation-frame pacing.
  let diagStart = 0, diagRaf = 0, diagUpdates = 0, diagCost = 0;
  let diagMaxCost = 0, diagSlowRaf = 0, diagLastRaf = 0;
  function status(text, error = false) {
    ui.status.textContent = text;
    ui.status.dataset.error = String(error);
  }
  function timeLabel(t) {
    const total = Math.max(0,Math.round(t/1000));
    return Math.floor(total/60).toString().padStart(2,"0") + ":" +
      (total%60).toString().padStart(2,"0");
  }
  function clock(t) {
    const start=Number(dataset?.metadata?.captured_at_ms);
    return Number.isFinite(start) && start>0 ?
      new Date(start+t).toLocaleTimeString("it-IT",{hour12:false}) : timeLabel(t);
  }
  function pause() {
    playing = false;
    ui.play.textContent = "▶ Riproduci";
  }
  function resetRenderer() {
    if (api) api.reset();
    rendered = -1;
    appliedRoute = null;
    appliedScene = null;
  }
  function projectWithLvgl(frame, nextIndex) {
    if (!api) return;
    const updateStarted=performance.now();
    if (nextIndex < rendered || rendered === -1) resetRenderer();
    const {snapshot:s,route,scene} = frame;
    // Non ricostruire centinaia di vertici ad ogni snapshot: una geometria
    // viene applicata solo quando l'evento Android la modifica realmente.
    if (route !== appliedRoute) {
      if (route) {
        api.routeBegin(route.origin[0],route.origin[1],route.generation,route.token);
        route.points.forEach(p=>api.routePoint(p[0],p[1]));
      } else resetRenderer();
      appliedRoute = route;
    }
    if (scene !== appliedScene) {
      api.sceneBegin(scene ? scene.revision : 0);
      if (scene) for (const r of scene.roads) {
        api.roadBegin();r.points.forEach(p=>api.roadPoint(p[0],p[1]));api.roadEnd(r.clazz);
      }
      appliedScene = scene;
    }
    const m = Object.hasOwn(manoeuvres,s.maneuver) ? manoeuvres[s.maneuver] :
      numeric(s.maneuver,0);
    api.snapshot(states[s.state] ?? 3, m, numeric(s.lat),numeric(s.lon),
      numeric(s.heading_deg),numeric(s.speed_mps),numeric(s.turn_m),
      numeric(s.remaining_m),numeric(s.total_m),numeric(s.duration_s),
      numeric(s.progress_m),numeric(s.speed_limit),numeric(s.exit),
      networks[s.network] ?? 0,s.has_turn===false?0:1);
    rendered = nextIndex;
    const cost=performance.now()-updateStarted;
    diagUpdates++;
    diagCost+=cost;
    diagMaxCost=Math.max(diagMaxCost,cost);
  }
  function annotation(t) {
    // Prototipo della conferma per 7 s, sul SOLO renderer geometrico 2D.
    // Non simula una notifica ESP32 già implementata.
    for (let i=events.length-1;i>=0;i--) {
      const e=events[i];
      if (e.t>t) continue;
      return e.kind==="waypoint" && t-e.t<=7000 ? e : null;
    }
    return null;
  }
  function renderEventList() {
    ui.events.replaceChildren();
    if (!events.length) { ui.events.textContent="Nessun evento individuato";return; }
    for (const e of events) {
      const button=document.createElement("button");
      button.type="button";
      button.className="event event-"+e.kind;
      button.dataset.frame=String(e.frameIndex);
      button.title="Vai al frame " + (e.frameIndex+1);
      button.textContent=clock(e.t)+" · "+e.label;
      button.addEventListener("click",()=>{pause();display(e.frameIndex);});
      ui.events.append(button);
    }
  }
  function drawProfile() {
    if (!frames.length) return;
    const canvas=ui.profile, ctx=canvas.getContext("2d");
    if (!ctx) return;
    const w=canvas.width,h=canvas.height, t0=dataset.firstMs,
      duration=Math.max(1,dataset.lastMs-t0), x=t=>5+(w-10)*(t-t0)/duration;
    ctx.clearRect(0,0,w,h);
    ctx.fillStyle="#0a151a";ctx.fillRect(0,0,w,h);
    const top=9,bottom=h-19,plotH=bottom-top;
    const speeds=profileSpeeds;
    const maxSpeed=profileMaxSpeed;
    ctx.strokeStyle="#34464f";ctx.lineWidth=1;
    for(let k=0;k<=4;k++){const y=top+k*plotH/4;
      ctx.beginPath();ctx.moveTo(0,y);ctx.lineTo(w,y);ctx.stroke();}
    ctx.beginPath();ctx.strokeStyle="#72aeca";ctx.lineWidth=1.7;
    frames.forEach((f,i)=>{const y=bottom-Math.min(maxSpeed,speeds[i])/maxSpeed*plotH;
      i?ctx.lineTo(x(f.t),y):ctx.moveTo(x(f.t),y);});ctx.stroke();
    for(const e of events){
      ctx.strokeStyle=e.kind==="reroute"?"#ffa664":e.kind==="waypoint"?"#68df9e":
        e.kind==="arrived"?"#f3f4f4":"#7d8890";
      ctx.beginPath();ctx.moveTo(x(e.t),top);ctx.lineTo(x(e.t),bottom);ctx.stroke();
    }
    ctx.strokeStyle="#ffffff";ctx.lineWidth=2;
    ctx.beginPath();ctx.moveTo(x(frames[index].t),top-3);ctx.lineTo(x(frames[index].t),bottom+3);ctx.stroke();
    ctx.fillStyle="#a8b7c0";ctx.font="12px system-ui";ctx.textAlign="left";
    ctx.fillText("Velocità 0–"+maxSpeed+" km/h",8,h-4);
    ctx.textAlign="right";ctx.fillText(timeLabel(dataset.lastMs-t0),w-6,h-4);
  }
  function display(i) {
    if (!frames.length) return;
    index = Math.max(0,Math.min(frames.length-1,Math.trunc(Number(i)||0)));
    const f=frames[index],s=f.snapshot;
    if (api) projectWithLvgl(f,index);
    else fallback.draw(ui.preview,f,annotation(f.t));
    ui.timeline.value=String(index);
    ui.time.textContent=clock(f.t)+" · +"+timeLabel(f.t-dataset.firstMs);
    ui.frame.textContent=(index+1)+" / "+frames.length;
    const m=Object.hasOwn(manoeuvres,s.maneuver)?manoeuvres[s.maneuver]:numeric(s.maneuver,0);
    ui.maneuver.textContent=(labels[m]||String(s.maneuver||"—"))+" · "+
      Math.round(numeric(s.turn_m))+" m";
    ui.roads.textContent=(f.scene?f.scene.roads.length:0)+" strade / "+
      (f.route?f.route.points.length:0)+" vertici rotta";
    ui.zoom.textContent=(api?api.zoomFactor():
      fallback.zoomFactor(s,!!f.route)).toFixed(2)+"×";
    ui["speed-kph"].textContent=Math.round(numeric(s.speed_mps)*3.6)+" km/h";
    // The LVGL canvas is the priority during playback. Redrawing the entire
    // speed profile (thousands of vertices) every GPS snapshot causes jank
    // in browsers, especially on older Windows machines.
    const now=performance.now();
    if (!playing || now-lastProfilePaint>=120) {
      const current=events.filter(e=>e.frameIndex<=index).at(-1);
      const activeFrame=current?.frameIndex ?? -1;
      if (lastActiveEventFrame!==activeFrame) {
        for(const button of ui.events.children){
          const eventFrame=Number(button.dataset.frame);
          button.classList.toggle("active",activeFrame===eventFrame);
        }
        lastActiveEventFrame=activeFrame;
      }
      drawProfile();
      lastProfilePaint=now;
    }
  }
  function load(source,name) {
    const result=model.parse(source);
    pause();dataset=result;frames=result.frames;events=result.events;index=0;
    profileSpeeds=frames.map(f=>Math.max(0,numeric(f.snapshot.speed_mps)*3.6));
    profileMaxSpeed=Math.max(40,Math.ceil(
      profileSpeeds.reduce((a,b)=>Math.max(a,b),0)/20)*20);
    resetRenderer();
    lastMotionPaint=-Infinity;
    diagStart=0;diagRaf=0;diagUpdates=0;diagCost=0;
    diagMaxCost=0;diagSlowRaf=0;diagLastRaf=0;
    ui.performance.textContent="—";
    ui["lvgl-performance"].textContent="—";
    lastProfilePaint=-Infinity;
    lastActiveEventFrame=null;
    ui.timeline.max=String(frames.length-1);
    ["play","previous","next","timeline","prev-event","next-event"].forEach(k=>ui[k].disabled=false);
    renderEventList();
    display(0);
    const reroutes=events.filter(e=>e.kind==="reroute").length;
    const roundabouts=events.filter(e=>e.kind==="roundabout").length;
    ui.summary.textContent=frames.length+" snapshot · "+result.counts.fix+" fix GPS · "+
      result.counts.scene+" scene · "+reroutes+" cambi rotta · "+roundabouts+
      " inizi guida rotatoria · "+result.counts.waypoint+" tappe";
    status(name+": "+timeLabel(result.durationMs)+" di guida registrata. "+
      (api?"Renderer LVGL WebAssembly attivo.":
      "Anteprima 2D attiva: per LVGL compila il runtime Emscripten."));
  }
  function step(delta){pause();display(index+delta);}
  function jumpEvent(delta){
    if(!events.length)return;
    const now=frames[index].t;
    const candidate=delta>0?events.find(e=>e.t>now+1):events.toReversed().find(e=>e.t<now-1);
    if(candidate){pause();display(candidate.frameIndex);}
  }
  // Replay snapshots are sparse/irregular compared with display refresh.
  // Interpolate ONLY screen motion between adjacent recorded GPS snapshots.
  // Manual stepping and seek always render unmodified recorded samples.
  let lastMotionPaint=-Infinity;
  function smoothMotion(target,now){
    // Follow the browser refresh cadence (typically 60 Hz). The previous
    // 40 ms gate forced only 25 distinct map positions per second even while
    // requestAnimationFrame ran at 60 Hz; measured WASM calls cost ~0.1 ms.
    if(!api || now-lastMotionPaint<14 || index>=frames.length-1)return;
    const a=frames[index],b=frames[index+1],dt=b.t-a.t;
    if(dt<70||dt>1500||a.route!==b.route||a.scene!==b.scene||
       a.snapshot.state!=="navigating"||b.snapshot.state!=="navigating")return;
    const u=Math.min(1,Math.max(0,(target-a.t)/dt));
    if(u<=0.001||u>=0.999)return;
    const sa=a.snapshot,sb=b.snapshot;
    const lat0=numeric(sa.lat,NaN),lon0=numeric(sa.lon,NaN);
    const lat1=numeric(sb.lat,NaN),lon1=numeric(sb.lon,NaN);
    if(![lat0,lon0,lat1,lon1].every(Number.isFinite))return;
    const meters=Math.hypot((lat1-lat0)*111195,
      (lon1-lon0)*111195*Math.cos(lat0*Math.PI/180));
    // No interpolation across a jump, reroute or uncertain GPS position.
    if(meters>35)return;
    const lerp=(x,y)=>x+(y-x)*u;
    const h0=numeric(sa.heading_deg),h1=numeric(sb.heading_deg);
    const dh=((h1-h0+540)%360)-180;
    const interpolated={...sa,lat:lerp(lat0,lat1),lon:lerp(lon0,lon1),
      heading_deg:(h0+u*dh+360)%360,
      speed_mps:lerp(numeric(sa.speed_mps),numeric(sb.speed_mps))};
    projectWithLvgl({snapshot:interpolated,route:a.route,scene:a.scene},index);
    lastMotionPaint=now;
  }
  function tick(now){
    if (playing && frames.length) {
      diagRaf++;
      if (diagLastRaf && now-diagLastRaf>50) diagSlowRaf++;
      diagLastRaf=now;
      if (!diagStart) diagStart=now;
    } else if (!playing) {
      diagLastRaf=0;
    }
    if(playing&&frames.length){
      const target=frames[0].t+base+(now-started)*numeric(ui.speed.value,1);
      let next=model.findFrameAtOrAfter(frames,target);
      if(next>0&&frames[next].t>target)next--;
      if(next!==index)display(next);
      smoothMotion(target,now);
      if(now-diagStart>=1000){
        const seconds=(now-diagStart)/1000;
        ui.performance.textContent=
          (diagRaf/seconds).toFixed(0)+" RAF/s · "+
          diagUpdates+" WASM/s · "+
          (diagUpdates?diagCost/diagUpdates:0).toFixed(1)+" ms medi · "+
          diagMaxCost.toFixed(1)+" ms max · "+
          diagSlowRaf+" ritardi";
        diagStart=now;diagRaf=0;diagUpdates=0;diagCost=0;
        diagMaxCost=0;diagSlowRaf=0;
        if (api?.lvglHz) {
          ui["lvgl-performance"].textContent=
            api.lvglHz()+" handler/s · "+
            api.lvglAvg().toFixed(1)+" ms medi · "+
            api.lvglMax().toFixed(1)+" ms max · "+
            api.lvglDelays()+" ritardi";
        }
      }
      if(index===frames.length-1)pause();
    }
    requestAnimationFrame(tick);
  }
  function play(){
    if(!frames.length)return;
    if(playing){pause();return;}
    if(index>=frames.length-1)display(0);
    base=frames[index].t-frames[0].t;
    started=performance.now();
    playing=true;ui.play.textContent="⏸ Pausa";
  }
  function synthetic(){
    // Geometria di test inventata; non rappresenta una strada OSM.
    const lat=44.392,lon=7.55,radius=.0002,ring=[],roads=[];
    for(let i=0;i<=12;i++){
      const a=-Math.PI/2+i*Math.PI*2/12;
      ring.push([lat+Math.sin(a)*radius,lon+Math.cos(a)*radius]);
    }
    roads.push({road_class:2,points:ring});
    for(let i=0;i<6;i++){
      const p=ring[i*2],a=-Math.PI/2+i*Math.PI*2/6;
      roads.push({road_class:3,points:[p,
        [p[0]+Math.sin(a)*.00034,p[1]+Math.cos(a)*.00034],
        [p[0]+Math.sin(a)*.00067,p[1]+Math.cos(a)*.00067]]});
    }
    const route=[[lat-.001,lon],[lat-radius,lon],...ring.slice(0,5),[lat,lon+.001]];
    const events=[{schema:"moto-replay-v1",type:"meta",t_ms:0,source:"synthetic"},
      {type:"route",t_ms:0,origin:route[0],generation:1,route_token:171,points:route},
      {type:"scene",t_ms:0,revision:1,roads}];
    for(let i=0;i<=120;i++){
      const p=i/120,seg=Math.min(route.length-2,Math.floor(p*(route.length-1)));
      const frac=p*(route.length-1)-seg,a=route[seg],b=route[seg+1];
      const pos=[a[0]+(b[0]-a[0])*frac,a[1]+(b[1]-a[1])*frac];
      events.push({type:"snapshot",t_ms:i*250,lat:pos[0],lon:pos[1],
        heading_deg:i<55?0:i<84?(i-55)*3.1:90,speed_mps:i===120?0:8,
        state:i===120?"arrived":"navigating",maneuver:i<84?"roundabout":i===120?"arrive":"continue",
        has_turn:i<84,exit:3,turn_m:Math.max(0,92-i*1.1),
        remaining_m:Math.round((1-p)*550),total_m:550,
        progress_m:Math.round(p*550),duration_s:Math.round((1-p)*70),
        speed_limit:50,network:"offline"});
    }
    return JSON.stringify({schema:"moto-replay-v1",events});
  }
  function ready(){
    // Il callback Emscripten precede l'esecuzione di main(): lasciare un tick.
    setTimeout(()=>{
      try {
        const mod=window.Module;
        const fn=(name,args)=>mod.cwrap("moto_web_replay_"+name,null,args);
        api={reset:fn("reset",[]),
          routeBegin:fn("route_begin",["number","number","number","number"]),
          routePoint:fn("route_point",["number","number"]),
          sceneBegin:fn("scene_begin",["number"]),roadBegin:fn("road_begin",[]),
          roadPoint:fn("road_point",["number","number"]),roadEnd:fn("road_end",["number"]),
          snapshot:fn("snapshot",Array(15).fill("number")),
          curveZoom:fn("set_curve_zoom_enabled",["number"]),
          zoomFactor:mod.cwrap("moto_web_replay_zoom_factor","number",[]),
          lvglHz:mod.cwrap("moto_web_lvgl_handler_hz","number",[]),
          lvglDelays:mod.cwrap("moto_web_lvgl_handler_delays","number",[]),
          lvglAvg:mod.cwrap("moto_web_lvgl_handler_avg_ms","number",[]),
          lvglMax:mod.cwrap("moto_web_lvgl_handler_max_ms","number",[])};
        ui.preview.hidden=true;ui.canvas.hidden=false;
        ui["curve-zoom"].disabled=false;
        api.curveZoom(ui["curve-zoom"].checked?1:0);
        resetRenderer();
        if(frames.length)display(index);else load(synthetic(),"Esempio sintetico");
      } catch(error){api=null;ui.preview.hidden=false;
        status("Bridge WASM non disponibile: "+error.message,true);}
    },0);
  }
  ui["trace-file"].addEventListener("change",async()=>{
    const file=ui["trace-file"].files[0];if(!file)return;
    try{load(await file.text(),file.name);}
    catch(error){pause();status("Importazione fallita: "+error.message,true);}
  });
  ui["curve-zoom"].addEventListener("change",()=>{
    if (!api) return;
    api.curveZoom(ui["curve-zoom"].checked?1:0);
    if(frames.length)display(index);
  });
  ui.sample.addEventListener("click",()=>{
    try{load(synthetic(),"Esempio sintetico");}
    catch(error){status(error.message,true);}
  });
  ui.play.addEventListener("click",play);
  ui.previous.addEventListener("click",()=>step(-1));
  ui.next.addEventListener("click",()=>step(1));
  ui["prev-event"].addEventListener("click",()=>jumpEvent(-1));
  ui["next-event"].addEventListener("click",()=>jumpEvent(1));
  ui.timeline.addEventListener("input",()=>{pause();display(Number(ui.timeline.value));});
  ui.profile.addEventListener("click",e=>{
    if(!frames.length)return;
    const rect=ui.profile.getBoundingClientRect();
    const fraction=Math.max(0,Math.min(1,(e.clientX-rect.left)/rect.width));
    const t=dataset.firstMs+(dataset.lastMs-dataset.firstMs)*fraction;
    pause();display(model.findFrameAtOrAfter(frames,t));
  });
  ui.speed.addEventListener("change",()=>{
    if(playing&&frames.length){base=frames[index].t-frames[0].t;started=performance.now();}
  });
  ui.snapshot.addEventListener("click",()=>{
    const canvas=api?ui.canvas:ui.preview;
    try{canvas.toBlob(blob=>{
      if(!blob||!blob.size){status("Screenshot non disponibile",true);return;}
      const href=URL.createObjectURL(blob),a=document.createElement("a");
      a.href=href;a.download="moto-replay-"+(index+1)+(api?"-lvgl":"-anteprima")+".png";
      a.click();setTimeout(()=>URL.revokeObjectURL(href),5000);
    },"image/png");}
    catch(error){status("Screenshot: "+error.message,true);}
  });
  ui.canvas.hidden=true;
  requestAnimationFrame(tick);
  load(synthetic(),"Esempio sintetico");
  window.Module={canvas:ui.canvas,locateFile:path=>"../shell/runtime/"+path,
    print(){},printErr:error=>console.error(error),
    onAbort:reason=>status("WASM arrestato: "+reason,true),
    onRuntimeInitialized:ready};
  const script=document.createElement("script");
  script.src="../shell/runtime/moto-gps-device.js";
  script.onerror=()=>status("Runtime LVGL assente. L'anteprima 2D funziona; per il rendering reale compila scripts/build_web.sh.");
  document.body.append(script);
})();
