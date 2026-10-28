const claims = ZB.requireAuth("../auth/index.html");
document.getElementById("sidebar-mount").appendChild(ZB.renderSidebar("status", claims));
ZB.wireLogout("../auth/index.html");

document.getElementById("status-form").onsubmit = async (e)=>{
  e.preventDefault();
  const id = new FormData(e.target).get("id").trim();
  const btn = e.target.querySelector("button[type=submit]");
  const label = btn.querySelector(".btn-label");
  const result = document.getElementById("status-result");
  btn.disabled = true; label.innerHTML = `<i class="fa-solid fa-circle-notch spin"></i> Checking…`;
  result.innerHTML = "";
  try{
    const res = await ZB.api(`/transactions/${encodeURIComponent(id)}/status`);
    const d = res.data || res;
    result.appendChild(ZB.el(`
      <div class="txn">
        <div class="left">
          <div class="icon ${d.status==='COMPLETED'?'credit':'debit'}"><i class="fa-solid fa-receipt"></i></div>
          <div><div class="name">${ZB.escapeHtml(d.reference || id)}</div><div class="meta">Updated ${ZB.fmtDate(d.updatedAt || d.timestamp)}</div></div>
        </div>
        <span class="badge ${d.status==='COMPLETED'?'success':d.status==='FAILED'?'failed':'pending'}">${ZB.escapeHtml(d.status||'UNKNOWN')}</span>
      </div>`));
  }catch(err){
    result.appendChild(ZB.el(`<div class="empty"><i class="fa-solid fa-triangle-exclamation"></i>${ZB.escapeHtml(err.message)}</div>`));
  }finally{
    btn.disabled = false; label.textContent = "Check status";
  }
};
