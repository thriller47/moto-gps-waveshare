/* Replay locale: il rendering effettivo avviene nel WASM condiviso con ESP32. */
(() => {
  "use strict";
  const $ = id => document.getElementById(id);
  const ui = Object.fromEntries(["trace-file","sample","snapshot","status","play",
    "previous","next","speed","timeline","time","frame","maneuver","roads"].map(k => [k, $(k)]));
  const manoeuvres = {unknown:0, continue:1, keep_left:2, turn_left:3,
    sharp_left:4, u_turn:5, keep_right:6, turn_right:7, sharp_right:8,
    roundabout:10, exit:11, arrive:12};
  const labels = ["—","Dritto","Tieni sinistra","Sinistra","Sinistra stretta",
    "Inversione","Tieni destra","Destra","Destra stretta","Inversione",
    "Rotatoria","Uscita","Arrivo"];
  const states = {idle:0, acquiring:1, planning:2, navigating:3,
    rerouting:4, arrived:5};
  const networks = {offline:0, connecting:1, online:2};
  let api = null, frames = [], index = 0, playing = false, base = 0, started = 0;
  function status(t, error = false) {
    ui.status.textContent = t;
    ui.status.dataset.error = String(error);
  }
  function num(v, fallback = 0) {
    const n = Number(v);
    return Number.isFinite(n) ? n : fallback;
  }
  function point(p) {
    if (!Array.isArray(p) || p.length < 2) return null;
    const a = num(p[0], NaN), b = num(p[1], NaN);
    return Number.isFinite(a) && Number.isFinite(b) &&
      Math.abs(a) <= 90 && Math.abs(b) <= 180 ? [a,b] : null;
  }
  function routeEvent(e) {
    if (!Array.isArray(e.points)) return null;
    const points = e.points.map(point).filter(Boolean).slice(0,24);
    if (points.length < 2) return null;
    return {points, origin:point(e.origin) || points[0],
      token:num(e.route_token,1), generation:num(e.generation,1)};
  }
  function sceneEvent(e) {
    const roads = [];
    let available = 192;
    for (const r of (Array.isArray(e.roads) ? e.roads : []).slice(0,24)) {
      if (available < 2) break;
      const points = (Array.isArray(r.points) ? r.points : []).map(point)
        .filter(Boolean).slice(0,available);
      if (points.length < 2) continue;
      roads.push({points, clazz:num(r.road_class === undefined ? r.class : r.road_class,3)});
      available -= points.length;
    }
    return {revision:num(e.revision), roads};
  }
  function parse(source) {
    const data = source.trim();
    if (!data) throw Error("File vuoto");
    let records;
    try {
      const parsed = JSON.parse(data);
      if (parsed.schema && parsed.schema !== "moto-replay-v1")
        throw Error("Schema non supportato: " + parsed.schema);
      records = Array.isArray(parsed) ? parsed : parsed.events;
    } catch(e) {
      if (!data.includes("\n") || e.message.startsWith("Schema")) throw e;
      records = data.split(/\r?\n/).filter(Boolean).map((line,i) => {
        try { return JSON.parse(line); }
        catch { throw Error("JSONL non valido alla riga " + (i+1)); }
      });
    }
    if (!Array.isArray(records) || records.length > 100000)
      throw Error("Elenco eventi assente o troppo lungo");
    let route = null, scene = null;
    const result = [];
    records.map((e,i) => ({...e,original:i}))
      .sort((a,b) => num(a.t_ms)-num(b.t_ms) || a.original-b.original)
      .forEach(e => {
        if (e.schema && e.schema !== "moto-replay-v1")
          throw Error("Schema diverso da moto-replay-v1");
        if (e.type === "route") route = routeEvent(e);
        if (e.type === "scene") scene = sceneEvent(e);
        if (e.type === "snapshot") {
          const lat = num(e.lat,NaN), lon = num(e.lon,NaN);
          if (point([lat,lon])) result.push({t:num(e.t_ms), snapshot:e, route, scene});
        }
      });
    if (!result.length) throw Error("Nessuno snapshot con coordinate valide");
    return result;
  }
  function pause() {
    playing = false;
    ui.play.textContent = "▶ Riproduci";
  }
  function display(i) {
    if (!api || !frames.length) return;
    index = Math.min(frames.length - 1, Math.max(0,i));
    const f = frames[index], s = f.snapshot;
    api.reset();
    if (f.route) {
      const r = f.route;
      api.routeBegin(r.origin[0],r.origin[1],r.generation,r.token);
      r.points.forEach(p => api.routePoint(p[0],p[1]));
    }
    if (f.scene) {
      api.sceneBegin(f.scene.revision);
      f.scene.roads.forEach(r => {
        api.roadBegin();
        r.points.forEach(p => api.roadPoint(p[0],p[1]));
        api.roadEnd(r.clazz);
      });
    }
    const maneuver = manoeuvres[s.maneuver] === undefined ?
      num(s.maneuver,1) : manoeuvres[s.maneuver];
    api.snapshot(states[s.state] === undefined ? 3 : states[s.state],
      maneuver,num(s.lat),num(s.lon),num(s.heading_deg),num(s.speed_mps),
      num(s.turn_m),num(s.remaining_m),num(s.total_m),num(s.duration_s),
      num(s.progress_m),num(s.speed_limit),num(s.exit),
      networks[s.network] === undefined ? 0 : networks[s.network],
      s.has_turn === false ? 0 : 1);
    ui.timeline.value = String(index);
    ui.time.textContent = ((f.t - frames[0].t)/1000).toFixed(1) + " s";
    ui.frame.textContent = String(index+1) + " / " + frames.length;
    ui.maneuver.textContent = (labels[maneuver] || "—") +
      " · " + Math.round(num(s.turn_m)) + " m";
    ui.roads.textContent = (f.scene ? f.scene.roads.length : 0) + " strade / " +
      (f.route ? f.route.points.length : 0) + " vertici rotta";
  }
  function load(source,name) {
    if (!api) throw Error("Motore grafico non ancora pronto");
    pause();
    frames = parse(source);
    index=0;
    ui.timeline.max=String(frames.length-1);
    for (const key of ["play","previous","next","timeline"]) ui[key].disabled=false;
    display(0);
    status(name + ": " + frames.length + " frame in " +
      Math.round((frames.at(-1).t-frames[0].t)/1000) +
      " s. Replay dei messaggi registrati; non ricalcola la scelta delle strade Android.");
  }
  function step(offset) {
    pause();
    display(index+offset);
  }
  function tick(now) {
    if (playing && frames.length && api) {
      const t = frames[0].t + base + (now-started)*num(ui.speed.value,1);
      let lo = t < frames[index].t ? 0 : index, hi=frames.length-1;
      while (lo < hi) {
        const mid=Math.ceil((lo+hi)/2);
        if (frames[mid].t<=t) lo=mid; else hi=mid-1;
      }
      if (lo!==index) display(lo);
      if (index===frames.length-1) pause();
    }
    requestAnimationFrame(tick);
  }
  function play() {
    if (!frames.length || !api) return;
    if (playing) { pause();return; }
    if (index===frames.length-1) display(0);
    base=frames[index].t-frames[0].t;
    started=performance.now();
    playing=true;
    ui.play.textContent="⏸ Pausa";
  }
  function synthetic() {
    // Invented geometry, intentionally labelled synthetic and not from OSM.
    const lat=44.392, lon=7.55, radius=.0002, ring=[], roads=[];
    for(let i=0;i<=12;i++){
      const a=-Math.PI/2+i*Math.PI*2/12;
      ring.push([lat+Math.sin(a)*radius,lon+Math.cos(a)*radius]);
    }
    roads.push({road_class:2,points:ring});
    for(let i=0;i<6;i++){
      const p=ring[i*2], a=-Math.PI/2+i*Math.PI*2/6;
      roads.push({road_class:3,points:[p,
        [p[0]+Math.sin(a)*.00034,p[1]+Math.cos(a)*.00034],
        [p[0]+Math.sin(a)*.00067,p[1]+Math.cos(a)*.00067]]});
    }
    const start=[lat-.001,lon], end=[lat,lon+.001];
    const route=[start,[lat-radius,lon],...ring.slice(0,5),end];
    const events=[{schema:"moto-replay-v1",type:"meta",t_ms:0,source:"synthetic"},
      {type:"route",t_ms:0,origin:start,generation:1,route_token:171,points:route},
      {type:"scene",t_ms:0,revision:1,roads}];
    for(let i=0;i<=120;i++){
      const p=i/120, seg=Math.min(route.length-2,Math.floor(p*(route.length-1)));
      const frac=p*(route.length-1)-seg, a=route[seg], b=route[seg+1];
      const pos=[a[0]+(b[0]-a[0])*frac,a[1]+(b[1]-a[1])*frac];
      const approaching=i<84;
      events.push({type:"snapshot",t_ms:i*250,lat:pos[0],lon:pos[1],
        heading_deg:i<55?0:i<84?(i-55)*3.1:90,speed_mps:i===120?0:8,
        state:i===120?"arrived":"navigating",
        maneuver:approaching?"roundabout":i===120?"arrive":"continue",
        has_turn:approaching,exit:3,turn_m:Math.max(0,Math.round(92-i*1.1)),
        remaining_m:Math.round((1-p)*550),total_m:550,
        progress_m:Math.round(p*550),duration_s:Math.round((1-p)*70),
        speed_limit:50,network:"offline"});
    }
    return JSON.stringify({schema:"moto-replay-v1",events});
  }
  function ready() {
    // Emscripten fires onRuntimeInitialized before it calls C++ main().
    setTimeout(() => {
      try {
        const mod=window.Module;
        const fn=(name,args)=>mod.cwrap("moto_web_replay_"+name,null,args);
        api={reset:fn("reset",[]),
          routeBegin:fn("route_begin",["number","number","number","number"]),
          routePoint:fn("route_point",["number","number"]),
          sceneBegin:fn("scene_begin",["number"]),
          roadBegin:fn("road_begin",[]),
          roadPoint:fn("road_point",["number","number"]),
          roadEnd:fn("road_end",["number"]),
          snapshot:fn("snapshot",Array(15).fill("number"))};
        api.reset();
        load(synthetic(),"Esempio sintetico");
      } catch(e) { status("Errore bridge WASM: "+e.message,true); }
    },0);
  }
  ui["trace-file"].addEventListener("change",async()=>{
    const file=ui["trace-file"].files[0];
    if (!file) return;
    try { load(await file.text(),file.name); }
    catch(e) { pause();status("Importazione fallita: "+e.message,true); }
  });
  ui.sample.addEventListener("click",()=>{try{load(synthetic(),"Esempio sintetico");}
    catch(e){status(e.message,true);}});
  ui.play.addEventListener("click",play);
  ui.previous.addEventListener("click",()=>step(-1));
  ui.next.addEventListener("click",()=>step(1));
  ui.timeline.addEventListener("input",()=>{pause();display(Number(ui.timeline.value));});
  ui.speed.addEventListener("change",()=>{
    if (!playing || !frames.length) return;
    base=frames[index].t-frames[0].t;started=performance.now();
  });
  ui.snapshot.addEventListener("click",()=>{
    if (!api) return;
    try {
      $("canvas").toBlob(blob=>{
        if (!blob || !blob.size) {status("Frame WebGL non disponibile",true);return;}
        const href=URL.createObjectURL(blob),a=document.createElement("a");
        a.href=href;a.download="moto-replay-"+(index+1)+".png";a.click();
        setTimeout(()=>URL.revokeObjectURL(href),5000);
      },"image/png");
    } catch(e){status("Screenshot: "+e.message,true);}
  });
  requestAnimationFrame(tick);
  window.Module={canvas:$("canvas"),locateFile:path=>"../shell/runtime/"+path,
    print(){},printErr:error=>console.error(error),
    onAbort:reason=>status("WASM arrestato: "+reason,true),
    onRuntimeInitialized:ready};
  const script=document.createElement("script");
  script.src="../shell/runtime/moto-gps-device.js";
  script.onerror=()=>status("Runtime WASM assente: compila scripts/build_web.sh e apri con HTTP.",true);
  document.body.append(script);
})();