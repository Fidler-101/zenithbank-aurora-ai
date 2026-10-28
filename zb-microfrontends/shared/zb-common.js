/* ============================================================
   ZenithBank shared client library ("zb-common")
   Every micro-frontend loads this the same way it would import
   a small internal SDK package. It knows how to talk to the
   gateway and how to read/write the session token — nothing
   about any specific service's screens. Each micro-frontend is
   still free to be redeployed, rewritten, or replaced on its own;
   this file only changes when the *contract* changes.

   SERVICE MAP (via API Gateway on :8080)
     auth/      -> customer-service     (/api/customers/**)
     banking/   -> banking-service      (/api/banking/**)
     payments/  -> payment-service      (/api/payments/**)
     status/    -> transaction-status   (/api/transactions/**)
     aurora/    -> aurora-ai-service    (/api/aurora/**)
   ============================================================ */
window.ZB = (function(){
  /* Derived from wherever this page was loaded from, not hardcoded.
     "localhost:8080" only ever means the visitor's own machine — once
     this is served from an EC2 box, a hardcoded localhost URL would
     silently point every API call back at the visitor's laptop instead
     of your server. Gateway is assumed to run on port 8080 on the same
     host that served this page (matches the docker-compose setup). */
  const GATEWAY_PORT = 8080;
  const API_BASE = `${window.location.protocol}//${window.location.hostname}:${GATEWAY_PORT}/api`;
  const TOKEN_KEY = "zb_session_token";
  let memToken = null; // fallback when storage is blocked (e.g. sandboxed preview)

  function setToken(t){
    memToken = t;
    try{ localStorage.setItem(TOKEN_KEY, t); }catch(e){ /* storage unavailable — memory only */ }
  }
  function getToken(){
    try{
      const stored = localStorage.getItem(TOKEN_KEY);
      if(stored) return stored;
    }catch(e){ /* fall through to memory */ }
    return memToken;
  }
  function clearToken(){
    memToken = null;
    try{ localStorage.removeItem(TOKEN_KEY); }catch(e){}
  }
  function decodeJwt(token){
    try{
      const payload = token.split(".")[1];
      return JSON.parse(atob(payload.replace(/-/g,"+").replace(/_/g,"/")));
    }catch(e){ return null; }
  }

  /** Call at the top of any protected micro-frontend page.
   *  Redirects to the auth app if there's no session, and
   *  returns the decoded claims otherwise. */
  function requireAuth(loginUrl){
    const t = getToken();
    if(!t){ window.location.href = loginUrl; return null; }
    const claims = decodeJwt(t);
    if(!claims){ clearToken(); window.location.href = loginUrl; return null; }
    return claims;
  }

  async function api(path, {method="GET", body, auth=true} = {}){
    const headers = {"Content-Type":"application/json"};
    const t = getToken();
    if(auth && t) headers["Authorization"] = "Bearer " + t;
    let res;
    try{
      res = await fetch(API_BASE + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
    }catch(networkErr){
      throw new Error("Can't reach the API Gateway at " + API_BASE + ". Is it running?");
    }
    let json = null;
    try{ json = await res.json(); }catch(e){ /* no body */ }
    if(res.status === 401){
      clearToken();
      throw new Error("Session expired — please log in again.");
    }
    if(!res.ok){
      const msg = (json && (json.message || json.error)) || `Request failed (${res.status})`;
      throw new Error(msg);
    }
    return json;
  }

  function fmtMoney(v){
    const n = Number(v || 0);
    return "R " + n.toLocaleString("en-ZA", {minimumFractionDigits:2, maximumFractionDigits:2});
  }
  function fmtDate(d){
    if(!d) return "—";
    const dt = new Date(d);
    return dt.toLocaleDateString("en-ZA", {day:"2-digit", month:"short"}) + ", " +
           dt.toLocaleTimeString("en-ZA", {hour:"2-digit", minute:"2-digit"});
  }
  function el(html){ const t=document.createElement("template"); t.innerHTML=html.trim(); return t.content.firstElementChild; }
  function escapeHtml(s){ const d=document.createElement("div"); d.textContent=s; return d.innerHTML; }

  function toast(msg, type="default"){
    let box = document.getElementById("zb-toasts");
    if(!box){
      box = document.createElement("div"); box.id = "zb-toasts";
      document.body.appendChild(box);
    }
    const t = el(`<div class="zb-toast ${type==='error'?'error':type==='success'?'success':''}">
      <i class="fa-solid ${type==='error'?'fa-circle-exclamation':type==='success'?'fa-circle-check':'fa-info-circle'}"></i>
      <span>${escapeHtml(String(msg))}</span></div>`);
    box.appendChild(t);
    setTimeout(()=>{ t.style.transition="opacity .25s ease"; t.style.opacity="0"; setTimeout(()=>t.remove(),250); }, 3400);
  }

  /** Renders the shared sidebar shell. Each micro-frontend calls
   *  this with its own "active" key and mount point — the nav
   *  targets are plain hrefs to sibling apps, i.e. real page loads,
   *  not client-side routing across app boundaries. */
  function renderSidebar(active, claims){
    const items = [
      {key:"banking",  href:"../banking/index.html",  icon:"fa-gauge-high",              label:"Dashboard",          service:"banking-service"},
      {key:"payments", href:"../payments/index.html", icon:"fa-paper-plane",              label:"Payments",           service:"payment-service"},
      {key:"status",   href:"../status/index.html",   icon:"fa-magnifying-glass-dollar",  label:"Transaction status", service:"txn-status-service"},
      {key:"aurora",   href:"../aurora/index.html",   icon:"fa-sparkles",                 label:"Aurora AI",          service:"aurora-ai-service"},
    ];
    const navHtml = items.map(it => `
      <a class="nav-item ${it.key===active?'active':''}" href="${it.href}">
        <i class="fa-solid ${it.icon}"></i> ${it.label}
      </a>
    `).join("");
    return el(`
      <aside class="sidebar" id="zb-sidebar">
        <a class="brand" href="../banking/index.html"><i class="fa-solid fa-shield-halved"></i> ZenithBank</a>
        ${navHtml}
        <div class="sidebar-foot">
          <div class="sidebar-user">
            <div class="avatar">${(claims?.email||"?").slice(0,1).toUpperCase()}</div>
            <div style="overflow:hidden;">
              <div style="color:#fff;font-weight:600;font-size:12.5px;white-space:nowrap;text-overflow:ellipsis;overflow:hidden;">${claims?.email || "Signed in"}</div>
              <div>Customer #${claims?.sub || "—"}</div>
            </div>
          </div>
          <button class="nav-item" id="zb-logout"><i class="fa-solid fa-arrow-right-from-bracket"></i> Log out</button>
        </div>
      </aside>
    `);
  }

  function wireLogout(loginUrl){
    const btn = document.getElementById("zb-logout");
    if(btn) btn.onclick = ()=>{ clearToken(); window.location.href = loginUrl; };
  }

  return { API_BASE, setToken, getToken, clearToken, decodeJwt, requireAuth, api,
           fmtMoney, fmtDate, el, escapeHtml, toast, renderSidebar, wireLogout };
})();
