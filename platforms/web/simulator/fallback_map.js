/* Anteprima geometrica 2D: NON è LVGL, non misura prestazioni ESP32/BLE.
 * Mantiene scala, origine e orientamento del NavPresenter per poter ispezionare
 * un JSONL prima che il runtime WebAssembly sia stato compilato.
 */
(function(root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  if (root) root.MotoFallbackMap = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function() {
  "use strict";
  const WIDTH = 466, CENTER_X = WIDTH/2, CENTER_Y = 196 * WIDTH/360;
  const BASE_PIXELS_METRE = 0.44 * WIDTH / 360;
  function zoomFactor(s, hasRoute) {
    if (!hasRoute || s.state !== "navigating" || s.has_turn === false ||
        ["unknown","continue","arrive","none"].includes(s.maneuver)) return 1;
    const distance = Number(s.turn_m);
    if (!Number.isFinite(distance) || distance < 0 || distance > 100) return 1;
    return 1 + 3 * (100 - distance) / 100;
  }
  function project(p, s, zoom) {
    const lat = Number(s.lat), lon = Number(s.lon), h = Number(s.heading_deg)*Math.PI/180;
    const north = (p[0] - lat) * (Math.PI / 180 * 6371000);
    const east = (p[1] - lon) * (Math.PI / 180 * 6371000) * Math.cos(lat*Math.PI/180);
    const scale = BASE_PIXELS_METRE * zoom;
    return [CENTER_X + (east*Math.cos(h) - north*Math.sin(h))*scale,
      CENTER_Y - (east*Math.sin(h) + north*Math.cos(h))*scale];
  }
  function draw(canvas, frame, annotation) {
    if (!canvas || !frame) return;
    const context = canvas.getContext("2d");
    if (!context) return;
    const {snapshot:s, route, scene} = frame;
    const z=zoomFactor(s,!!(route && route.points.length>=2));
    context.clearRect(0,0,WIDTH,WIDTH);
    context.fillStyle="#070e12";
    context.fillRect(0,0,WIDTH,WIDTH);
    context.save();
    context.beginPath();context.arc(CENTER_X,CENTER_X,CENTER_X-1,0,Math.PI*2);context.clip();
    const polyline = (points,color,width) => {
      if (!points || points.length<2) return;
      context.beginPath();
      points.forEach((p,i)=>{const [x,y]=project(p,s,z);i?context.lineTo(x,y):context.moveTo(x,y);});
      context.strokeStyle=color;context.lineWidth=width;context.lineCap="round";
      context.lineJoin="round";context.stroke();
    };
    if (scene) for (const road of scene.roads) {
      const color=road.clazz<=1?"#dae4e7":"#adbabe";
      polyline(road.points,color,road.clazz<=1?3.4:2.1);
    }
    if (route) {polyline(route.points,"#163e2f",11);polyline(route.points,"#4be498",6);}
    // Il simbolo di posizione non è una freccia di svolta.
    context.beginPath();context.arc(CENTER_X,CENTER_Y,9,0,Math.PI*2);
    context.fillStyle="#f5f8f6";context.fill();
    context.beginPath();context.arc(CENTER_X,CENTER_Y,4,0,Math.PI*2);
    context.fillStyle="#225a45";context.fill();
    if (annotation && annotation.kind==="waypoint") {
      context.fillStyle="rgba(10,27,22,.92)";
      context.fillRect(92,65,282,56);
      context.fillStyle="#80f0b0";context.font="bold 20px system-ui";
      context.textAlign="center";context.fillText(annotation.label.toUpperCase(),CENTER_X,99);
    }
    context.fillStyle="rgba(5,15,18,.88)";context.fillRect(33,16,400,29);
    context.fillStyle="#c9d5d8";context.textAlign="center";
    context.font="bold 12px system-ui";
    context.fillText("ANTEPRIMA 2D · NON LVGL",CENTER_X,35);
    context.fillStyle="rgba(5,15,18,.92)";context.fillRect(62,379,342,54);
    context.font="bold 16px system-ui";context.fillStyle="#ffffff";
    context.fillText(Math.max(0,Math.round(Number(s.turn_m)||0))+" m · "+z.toFixed(2)+"×",CENTER_X,401);
    context.font="12px system-ui";context.fillStyle="#b3c4c5";
    context.fillText(Math.round((Number(s.speed_mps)||0)*3.6)+" km/h · "+String(s.maneuver||"—"),CENTER_X,419);
    context.restore();
  }
  return {zoomFactor,project,draw};
});
