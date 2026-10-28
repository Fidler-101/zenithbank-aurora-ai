const claims = ZB.requireAuth("../auth/index.html");
document.getElementById("sidebar-mount").appendChild(ZB.renderSidebar("payments", claims));
ZB.wireLogout("../auth/index.html");

const content = document.getElementById("content");
content.innerHTML = `
  <div class="grid-2 view">
    <div class="card">
      <h3>Send a payment</h3>
      <div class="card-sub">This form belongs to payment-service — it never sees your account balance directly, only the account ID you're paying from.</div>
      <form id="pay-form">
        <div class="field"><label>From account ID</label><input name="fromAccountId" required placeholder="Paste the account UUID from your dashboard"></div>
        <div class="field"><label>Beneficiary account number</label><input name="toAccountNo" required placeholder="e.g. 62812345678"></div>
        <div class="field"><label>Branch code</label><input name="branchCode" required placeholder="e.g. 250655"></div>
        <div class="row-2">
          <div class="field"><label>Amount (ZAR)</label><input name="amount" type="number" step="0.01" min="0.01" required></div>
          <div class="field"><label>Payment date</label><input name="paymentDate" type="date" required></div>
        </div>
        <div class="field"><label>Reference</label><input name="reference" maxlength="30" placeholder="What's this for?"></div>
        <div class="field" style="display:flex;align-items:center;gap:8px;">
          <input type="checkbox" name="isRecurring" id="recurring-cb" style="width:auto;">
          <label for="recurring-cb" style="margin:0;">Make this a recurring payment</label>
        </div>
        <div class="field hidden" id="freq-field">
          <label>Frequency</label>
          <select name="frequency"><option value="WEEKLY">Weekly</option><option value="MONTHLY" selected>Monthly</option></select>
        </div>
        <button type="submit" class="btn btn-accent btn-block"><span class="btn-label">Send payment</span></button>
      </form>
    </div>
    <div class="card">
      <h3>Scheduled &amp; recent</h3>
      <div class="card-sub">Payments you've queued or sent recently.</div>
      <div id="sched-list"><div class="skel" style="height:56px;margin-bottom:8px;"></div></div>
    </div>
  </div>
  <div class="card" style="margin-top:20px;max-width:520px;">
    <h3>Where's my account ID?</h3>
    <div class="card-sub" style="margin-bottom:0;">
      Payment-service intentionally doesn't fetch your balance — that's banking-service's job.
      Grab the account ID from the <a href="../banking/index.html" style="color:var(--emerald-dark);font-weight:600;">dashboard app</a> first.
    </div>
  </div>
`;

const recurringCb = document.getElementById("recurring-cb");
const freqField = document.getElementById("freq-field");
recurringCb.onchange = ()=> freqField.classList.toggle("hidden", !recurringCb.checked);

document.getElementById("pay-form").onsubmit = async (e)=>{
  e.preventDefault();
  const fd = new FormData(e.target);
  const body = {
    fromAccountId: fd.get("fromAccountId"),
    toAccountNo: fd.get("toAccountNo"),
    branchCode: fd.get("branchCode"),
    amount: parseFloat(fd.get("amount")),
    reference: fd.get("reference") || undefined,
    paymentDate: fd.get("paymentDate"),
    isRecurring: !!fd.get("isRecurring"),
    frequency: fd.get("isRecurring") ? fd.get("frequency") : undefined
  };
  const btn = e.target.querySelector("button[type=submit]");
  const label = btn.querySelector(".btn-label");
  btn.disabled = true; label.innerHTML = `<i class="fa-solid fa-circle-notch spin"></i> Sending…`;
  try{
    const res = await ZB.api("/payments/create", {method:"POST", body});
    ZB.toast(res.message || "Payment submitted.", "success");
    e.target.reset();
    freqField.classList.add("hidden");
    loadScheduled();
  }catch(err){
    ZB.toast(err.message, "error");
  }finally{
    btn.disabled = false; label.textContent = "Send payment";
  }
};

async function loadScheduled(){
  const mount = document.getElementById("sched-list");
  try{
    const res = await ZB.api("/payments/scheduled");
    const items = res.data || [];
    mount.innerHTML = "";
    if(!items.length){ mount.appendChild(ZB.el(`<div class="empty"><i class="fa-solid fa-calendar"></i>No scheduled payments.</div>`)); return; }
    items.forEach(p=>{
      const row = ZB.el(`
        <div class="txn" style="margin-bottom:8px;">
          <div class="left">
            <div class="icon debit"><i class="fa-solid fa-paper-plane"></i></div>
            <div><div class="name">${ZB.escapeHtml(p.toAccountNo || p.reference || "Payment")}</div><div class="meta">${ZB.escapeHtml(p.paymentDate || "")} · ${ZB.escapeHtml(p.reference || "—")}</div></div>
          </div>
          <div style="display:flex;align-items:center;gap:10px;">
            <span class="badge ${p.status==='COMPLETED'?'success':p.status==='FAILED'?'failed':'pending'}">${ZB.escapeHtml(p.status||'PENDING')}</span>
            <button class="btn btn-danger" data-cancel style="padding:6px 10px;font-size:12px;">Cancel</button>
          </div>
        </div>`);
      row.querySelector("[data-cancel]").onclick = async ()=>{
        try{ await ZB.api(`/payments/${p.id}/cancel`, {method:"DELETE"}); ZB.toast("Payment cancelled.", "success"); loadScheduled(); }
        catch(err){ ZB.toast(err.message, "error"); }
      };
      mount.appendChild(row);
    });
  }catch(err){
    mount.innerHTML = "";
    mount.appendChild(ZB.el(`<div class="empty">Couldn't load scheduled payments.</div>`));
  }
}
document.getElementById("refresh-btn").onclick = ()=>{ loadScheduled(); ZB.toast("Refreshed.", "success"); };
loadScheduled();
