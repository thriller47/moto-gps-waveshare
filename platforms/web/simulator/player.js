/* Moto Chat GPS: replay degli snapshot Android sul renderer reale LVGL (WASM).
 * Senza Emscripten mostra un'ANTEPRIMA GEOMETRICA 2D, chiaramente distinta.
 * I file caricati restano nel browser: nessun upload o salvataggio automatico.
 */
(() => {
  "use strict";
  const $ = id => document.getElementById(id);
  const names = ["trace-file","sample","snapshot","status","play","previous","next",
    "prev-event","next-event","speed","timeline","time","frame","maneuver",
    "roads","ring-alignment","zoom","speed-kph","gps-position","gps-heading","heading-mode","heading-mode-note","map-origin","prediction-offset","heading-chart","offset-chart","diagnostic-summary","export-heading","performance","lvgl-performance","map-motion","events","profile","canvas","preview","summary","curve-zoom","geometry-zoom","predictor-mode","prediction-three"];
  const ui = Object.fromEntries(names.map(id=>[id,$(id)]));
  const required = names.filter(id=>!ui[id]);
  if (required.length) throw Error("Controlli simulator mancanti: " + required.join(", "));
  const model = window.MotoReplayModel, fallback = window.MotoFallbackMap,
    ringAlignment = window.MotoRingAlignment, headingDiagnostic = window.MotoHeadingDiagnostics,
    headingStability = window.MotoHeadingStability;
  if (!model || !fallback || !ringAlignment || !headingDiagnostic || !headingStability) throw Error("Moduli replay non caricati");
  const manoeuvres = {unknown:0,continue:1,keep_left:2,slight_left:2,turn_left:3,
    sharp_left:4,u_turn:5,u_turn_left:5,keep_right:6,slight_right:6,turn_right:7,sharp_right:8,
    u_turn_right:9,roundabout:10,exit:11,arrive:12};
  const labels = ["—","Dritto","Tieni sinistra","Sinistra","Sinistra stretta",
    "Inversione","Tieni destra","Destra","Destra stretta","Inversione",
    "Rotatoria","Uscita","Arrivo"];
  const states = {idle:0,acquiring:1,planning:2,navigating:3,rerouting:4,arrived:5};
  const networks = {offline:0,connecting:1,online:2};
  const numeric = model.finite;
  let api = null, dataset = null, frames = [], events = [], motionSpans = [];
  let index = 0, rendered = -1, appliedRoute = null, appliedScene = null;
  let playing = false, base = 0, started = 0;
  let lastProfilePaint = -Infinity, lastActiveEventFrame = null;
  let profileSpeeds = [], profileMaxSpeed = 40;
  let diagnostics = [], predictorObservations = new Map(), lastDiagnosticDraw = 0;
  let stableHeadings = [], courseHeadings = [], consensusHeadings = [],
    consensusQuality = [], lockedHeading = NaN;
  function selectedHeading(s,t,interpolated=false){
    const raw=Number(s.heading_deg),mode=ui["heading-mode"].value;
    if(mode==="locked" && Number.isFinite(lockedHeading))return lockedHeading;
    if((mode==="stable"||mode==="course"||mode==="consensus") && stableHeadings.length){
      const values=mode==="course"?courseHeadings:
        mode==="consensus"?consensusHeadings:stableHeadings;
      return interpolated?headingStability.at(frames,values,t):
        values[index];
    }
    return raw;
  }
  function refreshHeadingMode(){
    const mode=ui["heading-mode"].value;
    ui["heading-mode-note"].textContent=mode==="locked"?
      "Bloccato a "+(Number.isFinite(lockedHeading)?lockedHeading.toFixed(1)+"°":"—")+
      ": diagnostica; in curva non segue la moto.":
      mode==="stable"?"Stabilizzato: non tratta un semplice picco heading come una curva.":
      mode==="course"?"GPS verificato: segue il movimento tra fix distinti e limita rotazioni isolate.":
      mode==="consensus"?"Course multifix: almeno 16 m di movimento e curva confermata.":
      "Originale: heading registrato, senza filtro aggiuntivo.";
  }
  function analyzeFullReplay() {
    predictorObservations = new Map();
    if(!api?.batchReset || !api?.batchRoute || !api?.batchSample ||
       !api?.batchAlong || !api?.batchLateral || !frames.length) return;
    api.batchReset();
    let previousRoute=null, lastGeometryMs=NaN;
    for(let i=0;i<frames.length;i++) {
      const {route,snapshot:s,t}=frames[i];
      if(route!==previousRoute) {
        if(route) {
          api.batchRoute(route.origin[0],route.origin[1],
            route.generation,route.token);
          lastGeometryMs=t;
        } else {
          api.batchReset();
          lastGeometryMs=NaN;
        }
        previousRoute=route;
      }
      if(!route) continue;
      const on=api.batchSample(Number(s.lat),Number(s.lon),
        Number(s.heading_deg),Number(s.speed_mps),Number(t));
      const curve=api.batchCurveOffset?api.batchCurveOffset():NaN;
      const c2=api.batchC2Offset?api.batchC2Offset():NaN;
      predictorObservations.set(i,{
        off:0,on:Number.isFinite(on)?on:NaN,
        curve:Number.isFinite(curve)?curve:NaN,
        c2:Number.isFinite(c2)?c2:NaN,
        c2Along:Number.isFinite(c2)&&api.batchC2Along?api.batchC2Along():NaN,
        c2Lateral:Number.isFinite(c2)&&api.batchC2Lateral?api.batchC2Lateral():NaN,
        curveAlong:Number.isFinite(curve)&&api.batchCurveAlong?api.batchCurveAlong():NaN,
        curveLateral:Number.isFinite(curve)&&api.batchCurveLateral?api.batchCurveLateral():NaN,
        // Time since latest DISTINCT geometry in the parsed trace. The
        // parser deduplicates identical retransmissions, so this is NOT
        // physical BLE packet latency or the predictor's internal timer.
        geometryAgeMs:Number.isFinite(lastGeometryMs)?Math.max(0,t-lastGeometryMs):NaN,
        along:Number.isFinite(on)?api.batchAlong():NaN,
        lateral:Number.isFinite(on)?api.batchLateral():NaN
      });
    }
  }
  // Separate JS-to-WASM update cost from browser animation-frame pacing.
  let diagStart = 0, diagRaf = 0, diagUpdates = 0, diagCost = 0;
  let diagMaxCost = 0, diagSlowRaf = 0, diagLastRaf = 0;
  let diagInterpolated = 0, diagNoSpan = 0;
  let diagGapReasons = Object.create(null);
  let lastMapTargets = null, lastMapPixels = null;
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
    if (api) {
      api.reset();
      const mode=Number(ui["predictor-mode"].value);
      if(api.predictorMode)api.predictorMode(mode);
      else api.predictor(mode===1?1:0);
      api.geometryZoom?.(ui["geometry-zoom"].checked ? 1 : 0);
    }
    rendered = -1;
    appliedRoute = null;
    appliedScene = null;
  }
  function coordinateLabel(lat,lon) {
    return Number.isFinite(lat)&&Number.isFinite(lon) ?
      lat.toFixed(6)+", "+lon.toFixed(6) : "—";
  }
  function updatePositionTelemetry(s, interpolated=false) {
    const lat=Number(s.lat), lon=Number(s.lon), heading=Number(s.heading_deg);
    const valid=Number.isFinite(lat)&&Number.isFinite(lon);
    const tag=interpolated?" · interpolato JS":" · registrato";
    ui["gps-position"].textContent=coordinateLabel(lat,lon)+tag;
    const applied=selectedHeading(s,interpolated?Number(s.__replay_t):frames[index]?.t,interpolated);
    const headingLabel=v=>Number.isFinite(v)?headingStability.norm(v).toFixed(1)+"°":"—";
    ui["gps-heading"].textContent=headingLabel(heading)+" → "+headingLabel(applied)+
      tag+(api?" · NavPresenter":" · anteprima 2D")+
      (ui["heading-mode"].value==="consensus"&&consensusQuality[index]?
        " · "+consensusQuality[index].status+
        (consensusQuality[index].metres>0?" ("+consensusQuality[index].metres.toFixed(0)+" m)":""):"");
    if (!api) {
      ui["map-origin"].textContent="Non disponibile senza WASM";
      ui["prediction-offset"].textContent="Non disponibile senza WASM";
      return;
    }
    if(!api.originLat || !api.originLon){
      ui["map-origin"].textContent="N/D (WASM da ricompilare)";
      ui["prediction-offset"].textContent="N/D (telemetria opzionale assente)";
      return;
    }
    const a=api.originLat(),b=api.originLon();
    ui["map-origin"].textContent=coordinateLabel(a,b);
    if (!valid||!Number.isFinite(a)||!Number.isFinite(b)) {
      ui["prediction-offset"].textContent="— (coordinate non valide)";
      return;
    }
    const north=(a-lat)*111195, east=(b-lon)*111195*Math.cos(lat*Math.PI/180);
    const offset=Math.hypot(north,east);
    ui["prediction-offset"].textContent=offset.toFixed(1)+" m"+
      (" · modalità "+["B OFF","A originale","C curva","C2 curva/uscita"][Number(ui["predictor-mode"].value)]);
    // Batch CSV analysis uses independent state and never samples this
    // interactive renderer, so A/B data does not depend on the seek history.
  }
  function projectWithLvgl(frame, nextIndex, interpolated=false) {
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
    const appliedHeading=selectedHeading(s,frame.t,interpolated);
    api.snapshot(states[s.state] ?? 3, m, numeric(s.lat),numeric(s.lon),
      numeric(appliedHeading),numeric(s.speed_mps),numeric(s.turn_m),
      numeric(s.remaining_m),numeric(s.total_m),numeric(s.duration_s),
      numeric(s.progress_m),numeric(s.speed_limit),numeric(s.exit),
      networks[s.network] ?? 0,s.has_turn===false?0:1,
      numeric(frame.t,0));
    rendered = nextIndex;
    updatePositionTelemetry({...s,__replay_t:frame.t},interpolated);
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
  function drawDiagnosticCharts(force=false) {
    if(!diagnostics.length)return;
    const now=performance.now();
    if(!force && now-lastDiagnosticDraw<140)return;
    lastDiagnosticDraw=now;
    const t0=dataset.firstMs,span=Math.max(1,dataset.lastMs-t0);
    const draw=(canvas,label,values,min,max,suffix)=>{
      const ctx=canvas.getContext("2d");if(!ctx)return;
      const w=canvas.width,h=canvas.height,left=35,right=w-9,top=24,bottom=h-24;
      ctx.clearRect(0,0,w,h);
      ctx.fillStyle="#aec3c9";ctx.font="12px system-ui";ctx.fillText(label,10,15);
      ctx.strokeStyle="#28414a";ctx.lineWidth=1;
      for(let k=0;k<=4;k++){const y=top+(bottom-top)*k/4;
        ctx.beginPath();ctx.moveTo(left,y);ctx.lineTo(right,y);ctx.stroke();}
      ctx.fillStyle="#94aab2";ctx.fillText(max.toFixed(0)+suffix,2,top+4);
      ctx.fillText(min.toFixed(0)+suffix,2,bottom);
      const x=t=>left+(right-left)*(t-t0)/span;
      const y=v=>bottom-(v-min)/(max-min)*(bottom-top);
      for(let j=0;j<values.length;j++){
        ctx.beginPath();ctx.strokeStyle=["#72c9a9","#f0b67b","#8b9dff"][j];
        let prevValid=false,prevT=0;
        for(let i=0;i<diagnostics.length;i++){
          const d=diagnostics[i],v=values[j](d);
          if(!Number.isFinite(v)){prevValid=false;continue;}
          // Do not draw a line across a large recording dropout.
          if(!prevValid||d.t-prevT>3000)ctx.moveTo(x(d.t),y(Math.max(min,Math.min(max,v))));
          else ctx.lineTo(x(d.t),y(Math.max(min,Math.min(max,v))));
          prevValid=true;prevT=d.t;
        }
        ctx.stroke();
      }
      ctx.strokeStyle="#e5edf1";ctx.lineWidth=1;
      const here=x(frames[index].t);ctx.beginPath();ctx.moveTo(here,top);ctx.lineTo(here,bottom);ctx.stroke();
      ctx.fillStyle="#95b9ba";ctx.fillText("+ "+timeLabel(frames[index].t-t0),w-83,15);
    };
    draw(ui["heading-chart"],"Heading snapshot (verde) / segmento rotta (arancio), gradi",
      [d=>d.gps,d=>d.routeHeading],0,360,"°");
    const motionMax=Math.max(30,Math.min(200,Math.ceil(diagnostics.reduce((m,d)=>
      Math.max(m,Number.isFinite(d.lateral)?d.lateral:0,Number.isFinite(d.moved)?d.moved:0),0)/20)*20));
    draw(ui["offset-chart"],"Distanza dalla finestra verde (verde) / movimento fix (arancio), m",
      [d=>d.lateral,d=>d.moved],0,motionMax,"m");
    const row=diagnostics[index],warnings=diagnostics.filter(d=>d.flags.length).length;
    const prediction=predictorObservations.get(index)||{};
    ui["prediction-three"].textContent=
      "A "+(Number.isFinite(prediction.on)?prediction.on.toFixed(1)+" m":"n/d")+
      " · B 0 m · C "+(Number.isFinite(prediction.curve)?
        prediction.curve.toFixed(1)+" m":"n/d")+
      " · C2 "+(Number.isFinite(prediction.c2)?
        prediction.c2.toFixed(1)+" m":"n/d (WASM da aggiornare)");
    const signed=x=>Number.isFinite(x)?x.toFixed(1):"n/d";
    ui["diagnostic-summary"].textContent=
      "Frame "+(index+1)+" · Δ heading/rotta "+
      (Number.isFinite(row.headingMismatch)?row.headingMismatch.toFixed(1)+"°":"n/d")+
      " · laterale "+(Number.isFinite(row.lateral)?row.lateral.toFixed(1)+" m":"n/d")+
      " · movimento "+(Number.isFinite(row.moved)?row.moved.toFixed(1)+" m":"n/d")+
      " · ancora "+(Number.isFinite(prediction.geometryAgeMs)?
        (prediction.geometryAgeMs/1000).toFixed(2)+" s":"n/d")+
      " · avanti/indietro "+signed(prediction.along)+" m"+
      " · destra/sinistra A "+signed(prediction.lateral)+" m"+
      " · destra/sinistra C "+signed(prediction.curveLateral)+" m"+
      " · destra/sinistra C2 "+signed(prediction.c2Lateral)+" m"+
      " · segnalazioni "+warnings+" frame (non necessariamente errori)"+
      (row.flags.length?" · QUI: "+row.flags.join(", "):"");
  }
  function display(i, project = true) {
    if (!frames.length) return;
    index = Math.max(0,Math.min(frames.length-1,Math.trunc(Number(i)||0)));
    const f=frames[index],s=f.snapshot;
    if (api) { if (project) projectWithLvgl(f,index); }
    else {
      const adjusted={...f,snapshot:{...s,heading_deg:selectedHeading(s,f.t)}};
      fallback.draw(ui.preview,adjusted,annotation(f.t));
      // Telemetry retains the original GPS heading.
      updatePositionTelemetry(s);
    }
    ui.timeline.value=String(index);
    ui.time.textContent=clock(f.t)+" · +"+timeLabel(f.t-dataset.firstMs);
    ui.frame.textContent=(index+1)+" / "+frames.length;
    const m=Object.hasOwn(manoeuvres,s.maneuver)?manoeuvres[s.maneuver]:numeric(s.maneuver,0);
    ui.maneuver.textContent=(labels[m]||String(s.maneuver||"—"))+" · "+
      Math.round(numeric(s.turn_m))+" m";
    ui.roads.textContent=(f.scene?f.scene.roads.length:0)+" strade / "+
      (f.route?f.route.points.length:0)+" vertici rotta";
    const ringDiagnostic = ringAlignment.inspect(f);
    const ringLabels = {
      "no-ring":"Nessun anello prioritario",
      "no-route":"Anello presente · finestra rotta assente",
      "aligned":"Anello coincidente con la finestra verde",
      "nearby":"Anello vicino, rotta esterna",
      "separated":"Anello esterno alla finestra verde"
    };
    ui["ring-alignment"].textContent =
      (Number.isFinite(ringDiagnostic.gapMeters) ?
        ringDiagnostic.gapMeters.toFixed(1)+" m · " : "") +
      ringLabels[ringDiagnostic.status];
    ui.zoom.textContent=(api?api.zoomFactor():
      fallback.zoomFactor(s,!!f.route)).toFixed(2)+"×";
    ui["speed-kph"].textContent=Math.round(numeric(s.speed_mps)*3.6)+" km/h";
    drawDiagnosticCharts(!playing);
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
    motionSpans=model.buildMotionSpans(frames);
    diagnostics=headingDiagnostic.analyze(frames);
    stableHeadings=headingStability.build(frames);
    courseHeadings=headingStability.buildCourseValidated(frames);
    const consensus=headingStability.buildDistanceConsensus(frames);
    consensusHeadings=consensus.values;
    consensusQuality=consensus.quality;
    lockedHeading=NaN;
    if(ui["heading-mode"].value==="locked")ui["heading-mode"].value="raw";
    refreshHeadingMode();
    analyzeFullReplay();
    lastDiagnosticDraw=0;
    profileSpeeds=frames.map(f=>Math.max(0,numeric(f.snapshot.speed_mps)*3.6));
    profileMaxSpeed=Math.max(40,Math.ceil(
      profileSpeeds.reduce((a,b)=>Math.max(a,b),0)/20)*20);
    resetRenderer();
    diagStart=0;diagRaf=0;diagUpdates=0;diagCost=0;
    diagMaxCost=0;diagSlowRaf=0;diagLastRaf=0;
    diagInterpolated=0;diagNoSpan=0;
    diagGapReasons=Object.create(null);
    ui.performance.textContent="—";
    ui["lvgl-performance"].textContent="—";
    ui["map-motion"].textContent="—";
    lastMapTargets=null;lastMapPixels=null;
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
  // The log contains several identical-position UI snapshots per GPS fix.
  // Interpolate between distinct recorded fixes, preserving the current
  // route/scene and the experimental maneuver/curve zoom untouched.
  function smoothMotion(target){
    if(!api)return;
    // One interpolation attempt per browser RAF, not a second 14 ms clock.
    // Near 60 Hz that threshold skipped otherwise-valid alternate frames.
    const motion=model.interpolateMotion(frames,motionSpans,index,target);
    if(!motion){
      diagNoSpan++;
      const reason=model.motionGapReason(motionSpans,index,target,frames);
      diagGapReasons[reason]=(diagGapReasons[reason]||0)+1;
      return false;
    }
    diagInterpolated++;
    const frame=frames[index];
    projectWithLvgl({snapshot:{...frame.snapshot,...motion},
      route:frame.route,scene:frame.scene,t:target},index,true);
    return true;
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
      // One WASM snapshot per RAF. Previously the original recorded sample
      // was sent first and immediately overwritten by interpolation, creating
      // two competing motion targets on each recorded-frame transition.
      const changed=next!==index;
      if(changed)display(next,false);
      if(!smoothMotion(target) && changed && api) {
        projectWithLvgl(frames[index],index);
      }
      if(now-diagStart>=1000){
        const seconds=(now-diagStart)/1000;
        ui.performance.textContent=
          (diagRaf/seconds).toFixed(0)+" RAF/s · "+
          diagUpdates+" WASM/s · "+
          (diagUpdates?diagCost/diagUpdates:0).toFixed(1)+" ms medi · "+
          diagMaxCost.toFixed(1)+" ms max · "+
          diagSlowRaf+" ritardi · "+
          diagInterpolated+" interp/s · "+diagNoSpan+" senza interp"+
          (diagNoSpan ? " ("+Object.entries(diagGapReasons)
            .sort((a,b)=>b[1]-a[1]).slice(0,3)
            .map(([reason,count])=>reason+":"+count).join(", ")+")" : "");
        diagStart=now;diagRaf=0;diagUpdates=0;diagCost=0;
        diagMaxCost=0;diagSlowRaf=0;
        diagInterpolated=0;diagNoSpan=0;
        diagGapReasons=Object.create(null);
        if (api?.mapTargets) {
          const targets=api.mapTargets(),pixels=api.mapPixels();
          if(lastMapTargets!==null && lastMapPixels!==null) {
            const dt=Math.max(0,targets-lastMapTargets);
            const dp=Math.max(0,pixels-lastMapPixels);
            ui["map-motion"].textContent=dt+" bersagli/s · "+
              dp+" movimenti pixel/s · "+api.mapLag().toFixed(1)+" px ritardo";
          }
          lastMapTargets=targets;lastMapPixels=pixels;
        }
        if (api?.lvglHz) {
          ui["lvgl-performance"].textContent=
            api.lvglHz()+" handler/s · "+
            api.lvglRender()+" render/s · "+
            api.lvglFlush()+" flush/s · "+
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
        // Older WASM builds may lack the optional diagnostic exports.
        // Never let missing telemetry disable the real LVGL renderer.
        const optional=(name,returnType,args)=>{
          try{return mod.cwrap(name,returnType,args);}catch{return null;}
        };
        api={reset:fn("reset",[]),
          routeBegin:fn("route_begin",["number","number","number","number"]),
          routePoint:fn("route_point",["number","number"]),
          sceneBegin:fn("scene_begin",["number"]),roadBegin:fn("road_begin",[]),
          roadPoint:fn("road_point",["number","number"]),roadEnd:fn("road_end",["number"]),
          snapshot:fn("snapshot",Array(16).fill("number")),
          predictor:fn("set_predictor_enabled",["number"]),
          predictorMode:optional("moto_web_replay_set_predictor_mode",null,["number"]),
          batchReset:optional("moto_web_replay_batch_reset",null,[]),
          batchRoute:optional("moto_web_replay_batch_route",null,
            ["number","number","number","number"]),
          batchSample:optional("moto_web_replay_batch_sample","number",
            ["number","number","number","number","number"]),
          batchAlong:optional("moto_web_replay_batch_along_m","number",[]),
          batchLateral:optional("moto_web_replay_batch_lateral_m","number",[]),
          batchCurveOffset:optional("moto_web_replay_batch_curve_offset_m","number",[]),
          batchCurveAlong:optional("moto_web_replay_batch_curve_along_m","number",[]),
          batchCurveLateral:optional("moto_web_replay_batch_curve_lateral_m","number",[]),
          batchC2Offset:optional("moto_web_replay_batch_c2_offset_m","number",[]),
          batchC2Along:optional("moto_web_replay_batch_c2_along_m","number",[]),
          batchC2Lateral:optional("moto_web_replay_batch_c2_lateral_m","number",[]),
          originLat:optional("moto_web_replay_origin_lat","number",[]),
          originLon:optional("moto_web_replay_origin_lon","number",[]),
          curveZoom:fn("set_curve_zoom_enabled",["number"]),
          geometryZoom:optional("moto_web_replay_set_geometry_zoom_enabled",null,["number"]),
          zoomFactor:mod.cwrap("moto_web_replay_zoom_factor","number",[]),
          lvglRender:mod.cwrap("moto_web_lvgl_render_hz","number",[]),
          lvglFlush:mod.cwrap("moto_web_lvgl_flush_hz","number",[]),
          mapTargets:mod.cwrap("moto_nav_ui_web_target_changes","number",[]),
          mapPixels:mod.cwrap("moto_nav_ui_web_pixel_changes","number",[]),
          mapLag:mod.cwrap("moto_nav_ui_web_route_lag_px","number",[]),
          lvglHz:mod.cwrap("moto_web_lvgl_handler_hz","number",[]),
          lvglDelays:mod.cwrap("moto_web_lvgl_handler_delays","number",[]),
          lvglAvg:mod.cwrap("moto_web_lvgl_handler_avg_ms","number",[]),
          lvglMax:mod.cwrap("moto_web_lvgl_handler_max_ms","number",[])};
        ui.preview.hidden=true;ui.canvas.hidden=false;
        ui["curve-zoom"].disabled=false;
        ui["geometry-zoom"].disabled=!api.geometryZoom;
        ui["predictor-mode"].disabled=!api.predictorMode;
        if(!api.predictorMode)ui["predictor-mode"].value="0";
        api.curveZoom(ui["curve-zoom"].checked?1:0);
        resetRenderer();
        if(frames.length){analyzeFullReplay();display(index);}
        else load(synthetic(),"Esempio sintetico");
      } catch(error){api=null;ui.preview.hidden=false;
        status("Bridge WASM non disponibile: "+error.message,true);}
    },0);
  }
  ui["trace-file"].addEventListener("change",async()=>{
    const file=ui["trace-file"].files[0];if(!file)return;
    try{load(await file.text(),file.name);}
    catch(error){pause();status("Importazione fallita: "+error.message,true);}
  });
  ui["heading-mode"].addEventListener("change",()=>{
    pause();
    if(ui["heading-mode"].value==="locked"){
      const current=frames[index]?.snapshot;
      lockedHeading=current?Number(current.heading_deg):NaN;
    }
    refreshHeadingMode();
    resetRenderer();
    if(frames.length)display(index);
  });
  ui["predictor-mode"].addEventListener("change",()=>{
    if (!api) return;
    pause();resetRenderer();
    if(frames.length)display(index);
  });
  ui["geometry-zoom"].addEventListener("change",()=>{
    if (!api?.geometryZoom) return;
    pause();resetRenderer();
    if(frames.length)display(index);
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
  for(const id of ["heading-chart","offset-chart"]){
    ui[id].addEventListener("click",e=>{
      if(!frames.length)return;
      const canvas=ui[id],r=canvas.getBoundingClientRect();
      const fraction=Math.max(0,Math.min(1,(e.clientX-r.left)/r.width));
      pause();display(model.findFrameAtOrAfter(frames,dataset.firstMs+
        fraction*(dataset.lastMs-dataset.firstMs)));
    });
  }
  ui["export-heading"].addEventListener("click",()=>{
    if(!diagnostics.length)return;
    const contents="\uFEFF"+headingDiagnostic.csv(diagnostics,predictorObservations);
    const blob=new Blob([contents],{type:"text/csv;charset=utf-8"});
    const url=URL.createObjectURL(blob),anchor=document.createElement("a");
    anchor.href=url;anchor.download="moto-heading-diagnostics.csv";
    anchor.click();setTimeout(()=>URL.revokeObjectURL(url),5000);
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
