(function () {
  "use strict";

  const buildId = "20260905-realmap1";
  const runtimeUrl = `./runtime/moto-gps-device.js?v=${buildId}`;
  const controller = window.MotoNavController;
  if (!controller) return;

  window.MotoNavLiveBridge = Object.freeze({
    onRouteRequest(command) {
      controller.handleRouteRequest(command);
    },
    onTrafficRequest(command) {
      controller.handleTrafficRequest(command);
    },
  });

  function safeCwrap(module, ident, returnType, argTypes, fallback = () => {}) {
    try {
      if (typeof module.cwrap === "function") {
        const fn = module.cwrap(ident, returnType, argTypes);
        if (typeof fn === "function") return fn;
      }
    } catch (_) {}
    return fallback;
  }

  function createApi(module) {
    return Object.freeze({
      initialize: safeCwrap(module, "moto_web_live_initialize", null, []),
      start: safeCwrap(module, "moto_web_live_start", null, ["number", "number"]),
      cancel: safeCwrap(module, "moto_web_live_cancel", null, []),
      setNetwork: safeCwrap(module, "moto_web_live_set_network", null, ["number"]),
      fix: safeCwrap(module, "moto_web_live_fix", null, ["number", "number", "number", "number", "number", "number"]),
      tick: safeCwrap(module, "moto_web_live_tick", null, ["number"]),
      routeBegin: safeCwrap(module, "moto_web_live_route_begin", null, ["number", "string", "number", "number", "number"]),
      routeAddPoint: safeCwrap(module, "moto_web_live_route_add_point", null, ["number", "number"]),
      routeAddManeuver: safeCwrap(module, "moto_web_live_route_add_maneuver", null, ["number", "number", "number", "string", "string", "number"]),
      routeAddTraffic: safeCwrap(module, "moto_web_live_route_add_traffic", null, ["number", "number", "number"]),
      routeCommit: safeCwrap(module, "moto_web_live_route_commit", null, ["number"]),
      routeFailed: safeCwrap(module, "moto_web_live_route_failed", null, ["number", "number", "number"]),
      trafficBegin: safeCwrap(module, "moto_web_live_traffic_begin", null, ["number", "string", "number", "number"]),
      trafficAddSegment: safeCwrap(module, "moto_web_live_traffic_add_segment", null, ["number", "number", "number"]),
      trafficCommit: safeCwrap(module, "moto_web_live_traffic_commit", null, []),
      trafficFailed: safeCwrap(module, "moto_web_live_traffic_failed", null, ["number", "number"]),
      getNavState: safeCwrap(module, "moto_web_live_get_nav_state", "number", [], () => 0),
      getRemainingDistance: safeCwrap(module, "moto_web_live_get_remaining_distance", "number", [], () => 0),
      getRemainingDuration: safeCwrap(module, "moto_web_live_get_remaining_duration", "number", [], () => 0),
      getRouteGeneration: safeCwrap(module, "moto_web_live_get_route_generation", "number", [], () => 0),
      getRouteProgress: safeCwrap(module, "moto_web_live_get_route_progress", "number", [], () => 0),
      getOffRoute: safeCwrap(module, "moto_web_live_get_off_route", "number", [], () => 0),
      getPage: safeCwrap(module, "moto_web_get_page", "number", [], () => 0),
      demoScenario: safeCwrap(module, "moto_web_set_scenario", null, ["number"]),
      demoPage: safeCwrap(module, "moto_web_set_page", null, ["number"]),
      previewPhoneLifecycle: safeCwrap(module, "moto_web_preview_phone_lifecycle", null, ["number"]),
      setPhoneConnection: safeCwrap(module, "moto_web_set_phone_connection", null, ["number"]),
    });
  }

  function mountRuntime() {
    const canvas = controller.claimCanvas();
    window.Module = {
      canvas,
      locateFile(path) {
        return `./runtime/${path}?v=${buildId}`;
      },
      print() {},
      printErr(text) {
        if (String(text).startsWith("Looks like you are rendering without using requestAnimationFrame")) return;
        console.error(text);
      },
      onAbort(reason) {
        controller.runtimeFailed(`WASM 已中止：${String(reason)}`);
      },
      onExit(status) {
        if (status !== 0) controller.runtimeFailed(`WASM 异常退出：${status}`);
      },
      onRuntimeInitialized() {
        controller.attachRuntime(createApi(window.Module));
      },
    };

    const script = document.createElement("script");
    script.src = runtimeUrl;
    script.async = true;
    script.addEventListener("error", () => controller.runtimeFailed("WASM 加载失败"));
    document.body.append(script);
  }

  fetch(runtimeUrl, { method: "HEAD", cache: "no-store" })
    .then((response) => {
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      mountRuntime();
    })
    .catch(() => controller.runtimeFailed("共享导航画面未构建"));
})();
