/* Synthetic visual regression fixtures, not recorded GPS or GATT traffic. */
(function(root,factory) {
  const scenarios=factory();
  if(typeof module==="object" && module.exports) module.exports=scenarios;
  if(root) root.MotoSyntheticScenarios=scenarios;
})(typeof globalThis!=="undefined"?globalThis:this,function(){
  "use strict";
  const titles={
    normal:"Guida normale",
    reroute:"Deviazione e ricalcolo",
    tunnel:"Galleria / gap GPS simulato",
    uturn:"Strade parallele e inversione",
    long:"Percorso lungo / finestre",
    reconnect:"Perdita e ritorno link simulati"
  };
  function create(id) {
    if(!Object.hasOwn(titles,id))throw Error("Scenario sconosciuto");
    const lat=44.39,lon=7.55,scale=111195,lonScale=scale*Math.cos(lat*Math.PI/180);
    const geo=(x,y)=>[lat+y/scale,lon+x/lonScale];
    const base=id==="uturn"?[[0,0],[0,160],[8,180],[16,160],[16,0]]:
      [[0,0],[0,200],[100,250],[250,250]];
    const path=id==="long"?Array.from({length:21},(_,i)=>[i*500,(i%3)*50]):base;
    const events=[{type:"meta",schema:"moto-replay-v1",t_ms:0,source:"synthetic",scenario:id}];
    let token=171,revision=1,time=0,progress=0;
    const route=(points)=>{events.push({type:"route",t_ms:time,origin:geo(...points[0]),generation:1,route_token:token,points:points.map(p=>geo(...p))});events.push({type:"scene",t_ms:time,revision:revision++,roads:[{road_class:2,points:points.map(p=>geo(...p))}]});};
    let distance=path.slice(1).reduce((sum,p,i)=>sum+Math.hypot(p[0]-path[i][0],p[1]-path[i][1]),0);
    let rerouted=false, gap=false, disconnected=false;
    function snapshot(x,y,heading,speed,state,network,maneuver="continue") {
      const [lat,lon]=geo(x,y);
      events.push({type:"snapshot",t_ms:time,lat,lon,heading_deg:heading,speed_mps:speed,state,network,maneuver,
        has_turn:maneuver!=="continue"&&maneuver!=="arrive",turn_m:0,
        remaining_m:Math.max(0,distance-progress),total_m:distance,progress_m:progress,
        duration_s:Math.round(Math.max(0,distance-progress)/10),speed_limit:50});
    }
    route(path.slice(0,id==="long"?3:path.length));
    for(let i=0;i<path.length-1;i++) {
      const a=path[i],b=path[i+1],dx=b[0]-a[0],dy=b[1]-a[1],len=Math.hypot(dx,dy);
      const heading=(Math.atan2(dx,dy)*180/Math.PI+360)%360;
      if(id==="long"&&i>0)route(path.slice(i,Math.min(i+3,path.length)));
      const speed=id==="long"?25:10,steps=Math.max(1,Math.ceil(len/speed));
      for(let k=0;k<steps;k++){
        const x=a[0]+dx*k/steps,y=a[1]+dy*k/steps;
        if(id==="reroute"&&!rerouted&&i===1&&k===0){
          time+=1000;snapshot(x+40,y,heading,speed,"rerouting","online");
          token++;rerouted=true;route([a,...path.slice(i+1)]);
        }
        if(id==="tunnel"&&!gap&&i===0&&k===5){
          // The replay bridge has no live GNSS watchdog. A timestamp gap
          // tests interpolation boundaries, not the Android GNSS state machine.
          time+=8000;gap=true;
        }
        if(id==="reconnect"&&!disconnected&&i===0&&k===5){
          snapshot(x,y,heading,0,"idle","offline");time+=30000;
          snapshot(x,y,heading,0,"acquiring","connecting");time+=1000;
          route(path);disconnected=true;
        }
        snapshot(x,y,heading,speed,"navigating","online",id==="uturn"&&i===1?"u_turn":"continue");
        time+=1000;progress+=len/steps;
      }
    }
    const end=path.at(-1);progress=distance;snapshot(end[0],end[1],0,0,"arrived","online","arrive");
    return JSON.stringify({schema:"moto-replay-v1",events});
  }
  return {titles,create};
});
