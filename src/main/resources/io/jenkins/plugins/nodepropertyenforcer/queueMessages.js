// Explain visible pending builds without changing the queue, console output, or saved configuration.
(() => {
  const metadata = document.getElementById("node-property-queue");
  if (!metadata || typeof window.fetch !== "function") return;
  let data = {};
  let timer;
  let controller;
  let stopped = false;
  let interval = 30000;
  let requested = "";
  let resolved = new Set();
  const rendered = new WeakMap();
  const tooltips = new WeakMap();

  function rows() {
    const found = new Map();
    for (const row of document.querySelectorAll("#jenkins-build-history [page-entry-id].app-builds-container__item--not-interactable, #buildQueue tr")) {
      const cancel = row.querySelector('a[href*="/queue/cancelItem?id="]');
      const id = row.getAttribute("page-entry-id") || (cancel && new URL(cancel.href).searchParams.get("id"));
      if (id && /^[0-9]{1,18}$/.test(id) && found.size < 8) found.set(row, id);
    }
    return found;
  }

  function updateTooltip(row, messages) {
    const link = row.querySelector('a[tooltip]:not([data-html-tooltip]):not([href*="/queue/cancelItem"])');
    if (!link) return;
    if (!tooltips.has(link)) tooltips.set(link, link.getAttribute("tooltip"));
    const raw = messages.length ? messages.map(message => message.text).join("\n") : tooltips.get(link);
    link.setAttribute("tooltip", raw);
    const text = messages.length ? raw : raw.replace(/<br[ /]?\/?>|\\n/g, "\n");
    link.title = text;
    // Jenkins' plain-text tooltips use Tippy; also retain the native title fallback.
    if (link._tippy && typeof link._tippy.setProps === "function") {
      link._tippy.setProps({content: text, allowHTML: false});
    }
  }

  function render() {
    if (stopped || document.hidden) return;
    for (const [row, id] of rows()) {
      const messages = resolved.has(id) ? (data[id] || []) : [{text: "Checking this build's node requirements…"}];
      const signature = JSON.stringify(messages);
      if (rendered.get(row) === signature) continue;
      rendered.set(row, signature);
      updateTooltip(row, messages);
      const previous = row.querySelector(".node-property-queue-message");
      if (previous) previous.remove();
      const native = row.querySelector(".app-builds-container__item__description");
      if (native) native.hidden = messages.length > 0;
      if (!messages.length) continue;
      const notice = document.createElement("div");
      notice.className = "node-property-queue-message";
      notice.setAttribute("role", "status");
      for (const message of messages) {
        const line = document.createElement("div");
        line.textContent = message.text;
        if (message.url) {
          const link = document.createElement("a");
          link.textContent = "Configure node";
          link.href = message.url;
          line.append(" ", link);
        }
        notice.append(line);
      }
      (native ? row : row.querySelector("td")).append(notice);
    }
  }

  function schedule(delay = interval) {
    window.clearTimeout(timer);
    if (!stopped) timer = window.setTimeout(refresh, delay);
  }

  function reconcile() {
    render();
    const ids = [...new Set(rows().values())].join(",");
    if (!stopped && !document.hidden && !controller && ids && ids !== requested) schedule(100);
  }

  function refresh() {
    window.clearTimeout(timer);
    if (stopped || controller) return;
    const ids = [...new Set(rows().values())];
    if (document.hidden || !ids.length) {
      schedule();
      return;
    }
    requested = ids.join(",");
    controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 10000);
    fetch(metadata.dataset.statusUrl + "?ids=" + ids.join(","), {
      cache: "no-store", credentials: "same-origin", signal: controller.signal
    })
      .then(response => {
        if (!response.ok) throw new Error("Queue status unavailable");
        return response.json();
      })
      .then(payload => {
        const state = payload.data || payload;
        data = state.items || {};
        interval = Number.isFinite(state.refreshSeconds) ? Math.min(300, Math.max(15, state.refreshSeconds)) * 1000 : 30000;
      })
      .catch(() => {
        data = {}; // Restore Jenkins' current explanation if our request fails.
      })
      .finally(() => {
        window.clearTimeout(timeout);
        controller = null;
        resolved = new Set(ids);
        schedule();
        reconcile();
      });
  }

  const observer = new MutationObserver(reconcile);
  function start() {
    stopped = false;
    observer.observe(document.body, {childList: true, subtree: true});
    render();
    refresh();
  }
  document.addEventListener("visibilitychange", reconcile);
  window.addEventListener("pagehide", () => {
    stopped = true;
    window.clearTimeout(timer);
    observer.disconnect();
    if (controller) controller.abort();
  });
  window.addEventListener("pageshow", event => { if (event.persisted) start(); });
  start();
})();
