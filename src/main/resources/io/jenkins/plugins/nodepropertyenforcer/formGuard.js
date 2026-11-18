/* global Behaviour, crumb */
// Guidance for native node forms. Direct submissions remain checked on the server.
(() => {
  const metadata = document.getElementById("node-property-form-policy");
  if (!metadata) return;
  const policy = JSON.parse(metadata.dataset.policy);
  if (!policy.rules.length) return;
  let messageId = 0;
  const pending = new Map();
  const running = new Set();
  let disposed = false;
  window.addEventListener("pagehide", () => {
    disposed = true;
    pending.clear();
    running.forEach(record => record.controller.abort());
  });
  window.addEventListener("pageshow", () => { disposed = false; });

  function isNodeForm(form) {
    const path = new URL(form.action, document.baseURI).pathname;
    return !form.dataset.nodePolicyGuard && (/\/computer\/doCreateItem\/?$/.test(path)
      || (policy.checkEdits && /\/computer\/[^/]+\/configSubmit\/?$/.test(path)));
  }

  function nodeSection(form) {
    return Array.from(form.querySelectorAll("section[nameref]")).find(section => {
      const marker = document.getElementById(section.getAttribute("nameref"));
      return marker && marker.getAttribute("name") === "nodeProperties";
    });
  }

  function requiredOptions(form) {
    const section = nodeSection(form);
    const controls = section ? Array.from(section.querySelectorAll("input.optional-block-control")) : [];
    return policy.rules.map(rule => ({
      rule,
      control: controls.find(control => control.name === rule.name)
    }));
  }

  function addMessage(form, item) {
    const message = document.createElement("div");
    message.id = "node-property-required-" + ++messageId;
    message.className = "validation-error-area node-property-field-error";
    message.setAttribute("role", "alert");
    message.tabIndex = -1;
    message.hidden = true;
    const error = document.createElement("div");
    error.className = "error";
    error.textContent = item.control && !item.control.disabled
      ? "Enable this required option before saving."
      : item.rule.problem || item.rule.label + ": unavailable on this form. Review its plugin and required-option settings.";
    message.append(error);
    const row = item.control && item.control.closest(".optional-block-start");
    (row || nodeSection(form) || form).append(message);
    return {...item, message, creating: /\/doCreateItem\/?$/.test(new URL(form.action, document.baseURI).pathname),
      previousInvalid: item.control && item.control.getAttribute("aria-invalid")};
  }

  function addCreator(item, container, table) {
    const {rule} = item;
    if (!item.creating || !rule.creator || table.dataset.nodePolicyCreator) return;
    const name = "[USER:" + rule.creator + "]";
    const cards = table.querySelector(".mas-cards");
    let row = Array.from(cards ? cards.children : table.rows).find(candidate => candidate.getAttribute("name") === name);
    if (!row) {
      const button = container.querySelector('.matrix-auth-add-button[data-type="USER"]');
      const template = button && (cards
        ? Array.from(container.querySelectorAll("template[data-strategy-id]")).find(candidate => candidate.dataset.strategyId === table.id)
        : document.getElementById(button.dataset.templateId));
      if (!template) return;
      row = template.content.firstElementChild.cloneNode(true);
      row.removeAttribute("style");
      row.removeAttribute("id");
      row.setAttribute("name", name);
      if (cards) row.dataset.sid = rule.creator;
      (row.querySelector(".mas-card__name") || row.firstElementChild).textContent = rule.creator;
      // User IDs stay in textContent; native tooltip hooks keep static, safe descriptions.
      row.querySelectorAll("*").forEach(element => {
        Array.from(element.attributes).filter(attribute => attribute.name.includes("tooltip"))
          .forEach(attribute => element.setAttribute(attribute.name,
            attribute.value.replaceAll("__SID__", "node creator").replaceAll("__TYPE__", "User")));
      });
      (cards || table.tBodies[0]).append(row);
    }
    row.querySelectorAll('input[type="checkbox"]').forEach(input => {
      if (rule.permissions.includes(input.name.slice(1, -1))) input.checked = true;
    });
    table.dataset.nodePolicyCreator = "true";
    Behaviour.applySubtree(table, true);
  }

  function active(element) {
    return !element.closest('[field-disabled="true"],.form-container--hidden,template');
  }

  function visibleIn(root, selector) {
    return root ? Array.from(root.querySelectorAll(selector)).filter(active) : [];
  }

  function checkedValue(item, input, kind) {
    const value = input.value;
    item.valueChecks ||= new WeakMap();
    const previous = item.valueChecks.get(input);
    if (previous && previous.value === value && previous.kind === kind) return previous.message;
    if (previous && previous.controller) previous.controller.abort();
    pending.delete(input);
    item.valueChecks.delete(input);
    if (!value.trim()) return kind === "classes" ? item.rule.classMessage : "Enter a job-name expression.";
    const limit = kind === "classes" ? 1024 : 4096;
    if (value.length > limit) return `Keep the ${kind === "classes" ? "job class name" : "expression"} within ${limit} characters.`;
    const record = {value, kind, message: "Checking this value…"};
    item.valueChecks.set(input, record);
    if (pending.size >= 1024) {
      record.message = "Use fewer nested restrictions or list entries.";
      return record.message;
    }
    pending.set(input, {item, input, record});
    pump();
    return record.message;
  }

  function pump() {
    if (disposed) return;
    while (running.size < 4 && pending.size) {
      const [input, task] = pending.entries().next().value;
      pending.delete(input);
      if (document.contains(input)) requestValue(task);
    }
  }

  function requestValue({item, input, record}) {
      const {value, kind} = record;
      record.controller = new AbortController();
      running.add(record);
      const timeout = setTimeout(() => record.controller.abort(), 10000);
      fetch(metadata.dataset.valueUrl, {
        method: "POST", credentials: "same-origin", signal: record.controller.signal,
        headers: crumb.wrap({"Content-Type": "application/x-www-form-urlencoded"}),
        body: new URLSearchParams({value, kind}).toString()
      }).then(response => {
        if (!response.ok) throw new Error("Validation unavailable");
        return response.json();
      }).then(result => {
        if (!result.data || typeof result.data.message !== "string") throw new Error("Validation unavailable");
        record.message = result.data.message;
      }).catch(() => {
        record.message = "Could not check this value. Edit it to try again.";
      }).finally(() => {
        clearTimeout(timeout);
        record.controller = null;
        running.delete(record);
        if (!disposed && document.contains(input)) validate(item);
        pump();
      });
  }

  function restrictionStates(item, container) {
    const states = [];
    const nodes = visibleIn(container, "select.dropdownList").filter(select =>
      select.options.length === item.rule.restrictionLabels.length
      && Array.from(select.options).every((option, i) => option.textContent === item.rule.restrictionLabels[i])
    ).map(select => ({
      type: item.rule.restrictionTypes[Number(select.value)], target: select,
      root: select.closest(".jenkins-form-item").nextElementSibling
    }));
    visibleIn(container, '.repeated-chunk[name="restrictions"]').forEach(chunk => {
      nodes.push({type: chunk.getAttribute("descriptorid"),
        target: chunk.querySelector('.repeated-chunk__header') || chunk, root: chunk});
    });
    if (nodes.length + visibleIn(container, ".repeated-chunk").length > 1024) {
      return [{target: item.control, invalid: true, text: "Use fewer nested restrictions or list entries."}];
    }
    const add = (target, message) => states.push({target, invalid: Boolean(message), text: message});
    nodes.forEach(node => {
      const kind = item.rule.restrictionKinds[node.type];
      if (!node.type || kind === "any") {
        add(node.target, node.target.matches("select") ? item.rule.contentMessage
          : "Remove this ‘No restriction’ entry and add a restriction.");
      } else if (visibleIn(node.root, ".render-on-demand").length) {
        add(node.target, "Loading restriction settings…");
      } else if (kind === "expression") {
        const input = visibleIn(node.root, 'input[name="_.regexExpression"]')[0];
        add(input || node.target, input ? checkedValue(item, input, kind) : "Enter a job-name expression.");
      } else if (["multiple", "classes", "users", "groups"].includes(kind)) {
        listStates(item, node, kind, add);
      }
    });
    return states;
  }

  function listStates(item, node, kind, add) {
    const list = visibleIn(node.root, kind === "multiple" ? ".hetero-list-container" : ".repeated-container")[0];
    const rows = list ? Array.from(list.children).filter(child => child.classList.contains("repeated-chunk") && active(child)) : [];
    const button = list && list.querySelector("button.hetero-list-add,button.repeatable-add");
    const labels = {classes: "job class", users: "user ID", groups: "group ID"};
    if (!rows.length) add(button || node.target,
      kind === "multiple" ? item.rule.listMessage : "Add at least one " + labels[kind] + ".");
    if (rows.length > 100) add(button || node.target, item.rule.limitMessage);
    const fields = {classes: "selectedClass", users: "selectedUserId", groups: "selectedGroupId"};
    if (!fields[kind]) return;
    rows.forEach(row => visibleIn(row, 'input[name="_.' + fields[kind] + '"]').forEach(input => {
      const message = kind === "classes" ? checkedValue(item, input, kind)
        : (!input.value.trim() && (kind === "users" ? item.rule.userMessage : item.rule.groupMessage));
      add(input, message);
    }));
  }

  function contentStates(item) {
    const container = item.control.closest(".optionalBlock-container");
    if (!container) return [];
    if (item.rule.check === "matrix") {
      const table = container.querySelector("table.global-matrix-authorization-strategy-table, .mas-container");
      if (!table) return [];
      addCreator(item, container, table);
      const inputs = Array.from(table.querySelectorAll('tbody input[type="checkbox"], .mas-cards input[type="checkbox"]'));
      return [{target: table, text: item.rule.contentMessage, invalid: !inputs.some(input => input.checked
        && item.rule.permissions.includes(input.name.slice(1, -1)))}];
    }
    return restrictionStates(item, container);
  }

  function validateContent(item, enabled) {
    if (!item.rule.check) return false;
    const states = enabled ? contentStates(item) : [];
    item.contentMessages ||= new Map();
    item.contentMessages.forEach((message, target) => {
      if (!states.some(state => state.target === target)) showContentError(target, message, false);
      if (!document.contains(target)) item.contentMessages.delete(target);
    });
    let first;
    states.forEach(state => {
      let message = item.contentMessages.get(state.target);
      if (!message || !document.contains(message)) {
        message = item.message.cloneNode(true);
        message.id = "node-property-required-" + ++messageId;
        let anchor = state.target;
        if (anchor.matches("select")) anchor = anchor.closest(".jenkins-select") || anchor;
        else if (anchor.matches("input,textarea")) anchor = anchor.closest(".setting-main") || anchor;
        anchor.after(message);
        item.contentMessages.set(state.target, message);
      }
      if (state.text && message.firstElementChild.textContent !== state.text) message.firstElementChild.textContent = state.text;
      showContentError(state.target, message, state.invalid);
      if (state.invalid && !first) first = {...state, message};
    });
    if (first) {
      item.contentMessage = first.message;
      item.focusTarget = first.target.querySelector(".mas-card__header") || (first.target.tagName === "TABLE"
        ? first.target.querySelector('input[type="checkbox"]')
        : (first.target.matches("input,select,textarea,button") ? first.target : first.message));
    }
    return Boolean(first);
  }

  function showContentError(target, message, invalid) {
    message.hidden = !invalid;
    message.firstElementChild.className = invalid ? "error" : "";
    message.classList.toggle("validation-error-area--visible", invalid);
    target.setAttribute("aria-invalid", String(invalid));
    const descriptions = new Set((target.getAttribute("aria-describedby") || "").split(/\s+/).filter(Boolean));
    if (invalid) descriptions.add(message.id);
    else descriptions.delete(message.id);
    if (descriptions.size) target.setAttribute("aria-describedby", Array.from(descriptions).join(" "));
    else target.removeAttribute("aria-describedby");
  }

  function validate(item) {
    const {control, message, previousInvalid} = item;
    const missing = !control || !control.checked || control.disabled;
    message.hidden = !missing;
    message.firstElementChild.className = missing ? "error" : "";
    message.classList.toggle("validation-error-area--visible", missing);
    if (control) {
      if (missing) control.setAttribute("aria-invalid", "true");
      else if (previousInvalid === null) control.removeAttribute("aria-invalid");
      else control.setAttribute("aria-invalid", previousInvalid);
      const descriptions = new Set((control.getAttribute("aria-describedby") || "").split(/\s+/).filter(Boolean));
      if (missing) descriptions.add(message.id);
      else descriptions.delete(message.id);
      if (descriptions.size) control.setAttribute("aria-describedby", Array.from(descriptions).join(" "));
      else control.removeAttribute("aria-describedby");
    }
    item.focusTarget = control;
    return validateContent(item, !missing) || missing;
  }

  function install(form) {
    form.dataset.nodePolicyGuard = "true";
    const banner = document.getElementById("node-property-banner");
    if (banner) banner.hidden = true;
    const options = requiredOptions(form).map(item => addMessage(form, item));

    function guard(event) {
      const missing = options.filter(validate);
      if (!missing.length) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      const first = missing[0];
      const message = first.message.hidden ? first.contentMessage : first.message;
      message.scrollIntoView({block: "center"});
      const target = first.focusTarget && !first.focusTarget.disabled ? first.focusTarget : message;
      target.focus({preventScroll: true});
    }

    options.forEach(validate);
    form.addEventListener("submit", guard, true);
    form.addEventListener("click", event => {
      const button = event.target.closest("button,input");
      if (button && (button.name === "Submit" || button.getAttribute("type") === "submit"
        || button.classList.contains("apply-button"))) guard(event);
    }, true);
    form.addEventListener("change", () => options.forEach(validate));
    let validationTimer;
    function schedule(delay) {
      clearTimeout(validationTimer);
      validationTimer = setTimeout(() => options.forEach(validate), delay);
    }
    form.addEventListener("input", () => schedule(250));
    form.addEventListener("click", () => schedule(0));
    const observer = new MutationObserver(() => schedule(0));
    const observe = () => observer.observe(nodeSection(form) || form, {childList: true, subtree: true});
    observe();
    window.addEventListener("pagehide", () => {
      clearTimeout(validationTimer);
      observer.disconnect();
      options.forEach(item => { item.valueChecks = new WeakMap(); });
    });
    window.addEventListener("pageshow", event => {
      if (event.persisted) {
        observe();
        options.forEach(validate);
      }
    });
  }

  function initialize() {
    Array.from(document.forms).filter(isNodeForm).forEach(install);
  }

  // Jenkins assigns node-property sections and checkbox IDs during page initialization.
  if (document.readyState === "complete") initialize();
  else window.addEventListener("load", initialize, {once: true});
})();
