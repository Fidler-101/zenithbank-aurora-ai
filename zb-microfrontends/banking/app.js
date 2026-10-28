const claims = ZB.requireAuth("../auth/index.html");
let dashboard = null;
let activeAccountId = null;
let tab = "overview";

function mountSidebar(){
  const holder = document.getElementById("sidebar-mount");
  holder.innerHTML = "";
  holder.appendChild(ZB.renderSidebar("banking", claims));
  ZB.wireLogout("../auth/index.html");
}
document.getElementById("refresh-btn").onclick = async ()=>{ await load(); ZB.toast("Data refreshed.", "success"); };

async function load(){
  const content = document.getElementById("content");
  content.innerHTML = `<div class="skel" style="height:150px;border-radius:18px;margin-bottom:20px;"></div>
    <div class="quick-actions">${Array.from({length:4}).map(()=>`<div class="skel" style="height:80px;"></div>`).join("")}</div>`;
  try{
    const res = await ZB.api("/banking/dashboard");
    dashboard = res.data;
    if(dashboard?.accounts?.length) activeAccountId = dashboard.accounts[0].id;
    renderTab();
  }catch(err){
    ZB.toast(err.message, "error");
    content.innerHTML = `<div class="empty"><i class="fa-solid fa-triangle-exclamation"></i>${ZB.escapeHtml(err.message)}</div>`;
  }
}

function txnRow(t){
  const credit = t.credit;
  return ZB.el(`
    <div class="txn">
      <div class="left">
        <div class="icon ${credit?'credit':'debit'}"><i class="fa-solid ${credit?'fa-arrow-down':'fa-arrow-up'}"></i></div>
        <div><div class="name">${ZB.escapeHtml(t.counterpartyName || t.description || "Transaction")}</div>
        <div class="meta">${ZB.escapeHtml(t.category || "")} · ${ZB.fmtDate(t.initiatedAt)}</div></div>
      </div>
      <div class="amt ${credit?'credit':'debit'}">${credit?'+':'-'}${ZB.fmtMoney(Math.abs(t.amount))}</div>
    </div>`);
}

function renderTab(){
  const content = document.getElementById("content");
  content.innerHTML = "";
  content.classList.remove("view"); void content.offsetWidth; content.classList.add("view");

  const acc = (dashboard.accounts||[])[0] || {};
  const head = ZB.el(`
    <div>
      <div class="balance-card aurora-surface">
        <div class="label">${acc.accountType || "ACCOUNT"} · ${acc.accountNumber || "—"}</div>
        <div class="amount">${ZB.fmtMoney(acc.balance)}</div>
        <div class="acc">Available: ${ZB.fmtMoney(acc.availableBalance)}</div>
        <div class="pill"><i class="fa-solid fa-circle" style="font-size:6px;color:#5be2b8"></i> ${acc.status || "ACTIVE"}</div>
      </div>
      <div class="quick-actions">
        <a class="qa" href="../payments/index.html"><i class="fa-solid fa-paper-plane"></i><span>Make a payment</span></a>
        <a class="qa" href="../status/index.html"><i class="fa-solid fa-magnifying-glass-dollar"></i><span>Track a payment</span></a>
        <a class="qa" href="../aurora/index.html"><i class="fa-solid fa-sparkles"></i><span>Ask Aurora</span></a>
        <div class="qa" id="health-qa"><i class="fa-solid fa-heart-pulse"></i><span>Health score</span></div>
      </div>
      <div class="stat-row">
        <div class="stat-box"><div class="stat-label">Total balance</div><div class="stat-value">${ZB.fmtMoney(dashboard.totalBalance)}</div></div>
        <div class="stat-box"><div class="stat-label">Total available</div><div class="stat-value">${ZB.fmtMoney(dashboard.totalAvailable)}</div></div>
        <div class="stat-box"><div class="stat-label">Accounts</div><div class="stat-value">${dashboard.accountCount ?? (dashboard.accounts||[]).length}</div></div>
      </div>
      <div class="tabbar">
        <button class="tabbtn ${tab==='overview'?'active':''}" data-tab="overview">Recent activity</button>
        <button class="tabbtn ${tab==='history'?'active':''}" data-tab="history">Full history</button>
      </div>
      <div id="tab-body"></div>
    </div>
  `);
  content.appendChild(head);
  head.querySelector("#health-qa").onclick = showHealthScore;
  head.querySelectorAll(".tabbtn").forEach(b=>{
    b.onclick = ()=>{ tab = b.dataset.tab; renderTab(); };
  });

  const body = head.querySelector("#tab-body");
  if(tab === "overview"){
    const card = ZB.el(`<div class="card"><h3>Recent activity</h3><div class="card-sub">Last transactions on your ${(acc.accountType||"").toLowerCase()||"primary"} account</div><div class="txn-list" id="recent"></div></div>`);
    body.appendChild(card);
    const list = card.querySelector("#recent");
    const txns = acc.recentTransactions || [];
    if(!txns.length) list.appendChild(ZB.el(`<div class="empty"><i class="fa-solid fa-inbox"></i>No recent transactions yet.</div>`));
    else txns.forEach(t=>list.appendChild(txnRow(t)));
  }else{
    body.appendChild(historyPanel(acc));
  }
}

function historyPanel(acc){
  const wrap = ZB.el(`
    <div>
      <div class="card" style="margin-bottom:20px;">
        <h3>Filter</h3>
        <div class="card-sub">Ranges of 5 days or more are processed asynchronously — this page polls for you.</div>
        <form id="txn-filter" class="row-3">
          <div class="field"><label>From</label><input type="date" name="from"></div>
          <div class="field"><label>To</label><input type="date" name="to"></div>
          <div class="field" style="display:flex;align-items:flex-end;"><button class="btn btn-primary btn-block" type="submit">Apply</button></div>
        </form>
      </div>
      <div class="card">
        <h3>History</h3>
        <div class="card-sub" id="txn-status-line">Loading…</div>
        <div class="txn-list" id="txn-list"><div class="skel" style="height:56px;margin-bottom:8px;"></div></div>
      </div>
    </div>`);
  const list = wrap.querySelector("#txn-list");
  const statusLine = wrap.querySelector("#txn-status-line");

  async function loadHistory(params={}){
    if(!activeAccountId){ list.innerHTML=""; list.appendChild(ZB.el(`<div class="empty">No account found.</div>`)); return; }
    const qs = new URLSearchParams(params).toString();
    try{
      const res = await ZB.api(`/banking/accounts/${activeAccountId}/transactions${qs?`?${qs}`:""}`);
      const data = res.data;
      if(data && data.jobId){ statusLine.textContent = "Large date range — processing in the background…"; pollJob(data.jobId); return; }
      renderPage(data);
    }catch(err){ ZB.toast(err.message, "error"); statusLine.textContent = "Couldn't load transactions."; }
  }
  async function pollJob(jobId, attempt=0){
    if(attempt > 20){ statusLine.textContent = "Still processing — try refreshing shortly."; return; }
    try{
      const res = await ZB.api(`/banking/transactions/jobs/${jobId}`);
      const data = res.data;
      if(data && data.status === "COMPLETED") renderPage(data.result || data);
      else if(data && data.status === "FAILED") statusLine.textContent = "Background job failed — try a smaller range.";
      else setTimeout(()=>pollJob(jobId, attempt+1), 1200);
    }catch(err){ statusLine.textContent = "Couldn't check job status."; }
  }
  function renderPage(data){
    const items = (data && (data.transactions || data.content)) || [];
    list.innerHTML = "";
    statusLine.textContent = items.length ? `${items.length} transaction${items.length===1?"":"s"}` : "No transactions in this range.";
    if(!items.length) list.appendChild(ZB.el(`<div class="empty"><i class="fa-solid fa-inbox"></i>Nothing to show for this range.</div>`));
    else items.forEach(t=>list.appendChild(txnRow(t)));
  }
  wrap.querySelector("#txn-filter").onsubmit = (e)=>{
    e.preventDefault();
    const fd = new FormData(e.target);
    const params = {};
    if(fd.get("from")) params.from = fd.get("from");
    if(fd.get("to")) params.to = fd.get("to");
    statusLine.textContent = "Loading…";
    list.innerHTML = `<div class="skel" style="height:56px;"></div>`;
    loadHistory(params);
  };
  loadHistory();
  return wrap;
}

async function showHealthScore(){
  if(!activeAccountId) return;
  ZB.toast("Loading your financial health score…");
  try{
    const res = await ZB.api(`/banking/accounts/${activeAccountId}/health-score`);
    const d = res.data || {};
    alert(`Financial health score: ${d.score ?? "—"} / 100\n${d.summary || ""}`);
  }catch(err){ ZB.toast(err.message, "error"); }
}

mountSidebar();
load();
