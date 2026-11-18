// No form submissions, page reloads, console edits, or automatic disconnections.
(() => {
  const banner = document.getElementById("node-property-banner");
  const main = document.getElementById("main-panel");
  if (!banner || !main || typeof window.fetch !== "function") return;
  if (document.querySelector('form[data-node-policy-guard="true"]')) return;
  const heading = main.querySelector("h1");
  if (heading) heading.after(banner);
  else main.append(banner);
  let previous = "";
  let interval = 30000;
  let timer;
  let controller;
  let stopped = false;

  window.addEventListener("pagehide", () => {
    stopped = true;
    window.clearTimeout(timer);
    if (controller) controller.abort();
  });
  window.addEventListener("pageshow", event => {
    if (event.persisted) {
      stopped = false;
      refresh();
    }
  });

  function render(state) {
    if (document.querySelector('form[data-node-policy-guard="true"]')) {
      banner.hidden = true;
      return;
    }
    const signature = JSON.stringify(state);
    if (signature === previous) return;
    previous = signature;
    banner.replaceChildren();
    banner.hidden = state.problems.length === 0;
    if (banner.hidden) return;
    const title = document.createElement("strong");
    title.textContent = "Update node settings";
    banner.append(title);
    const explanation = document.createElement("p");
    const effect = state.blockBuilds
      ? "Complete the required settings on these nodes before starting new builds."
      : "These settings do not prevent new builds from starting.";
    explanation.textContent = state.buildPage
      ? (state.building ? "This plugin will not interrupt this build. " : "This build's result is unchanged. ") + effect
      : (state.blockBuilds
        ? "Complete this node's required settings before starting new builds."
        : "This node can start new builds while these settings are completed.");
    banner.append(explanation);
    for (const problem of state.problems) {
      const row = document.createElement("p");
      row.append(document.createTextNode(problem.node + ": " + problem.message + " "));
      if (problem.url) {
        const link = document.createElement("a");
        link.textContent = "Configure node";
        link.href = problem.url;
        row.append(link);
      } else {
        row.append(document.createTextNode("Node configuration access is required to make this change."));
      }
      banner.append(row);
    }
  }

  function refresh() {
    if (stopped || !document.contains(banner) || controller) return;
    if (document.hidden) {
      timer = window.setTimeout(refresh, interval);
      return;
    }
    controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 10000);
    fetch(banner.dataset.statusUrl, {cache: "no-store", credentials: "same-origin", signal: controller.signal})
      .then(response => {
        if (!response.ok) throw new Error("Status unavailable");
        return response.json();
      })
      .then(payload => {
        const state = payload.data || payload;
        if (!state || !Array.isArray(state.problems)) throw new Error("Invalid status response");
        interval = Number.isFinite(state.refreshSeconds) ? Math.min(300, Math.max(15, state.refreshSeconds)) * 1000 : 30000;
        if (!stopped) render(state);
      })
      .catch(() => { /* Keep any existing warning if a status request fails. */ })
      .finally(() => {
        window.clearTimeout(timeout);
        controller = null;
        if (!stopped) timer = window.setTimeout(refresh, interval);
      });
  }
  refresh();
})();
