const claims = ZB.requireAuth("../auth/index.html");
document.getElementById("sidebar-mount").appendChild(ZB.renderSidebar("aurora", claims));
ZB.wireLogout("../auth/index.html");

const sessionId = crypto.randomUUID ? crypto.randomUUID() : String(Date.now());
let history = [];
const log = document.getElementById("chat-log");

function paint(){
  log.innerHTML = "";
  if(!history.length){
    log.appendChild(ZB.el(`<div class="msg bot">Hi, I'm Aurora. Ask me anything about your balance, spending, or recent transactions — I only ever read your data, never change it.</div>`));
  }
  history.forEach(m=>{
    if(m.role === "user") log.appendChild(ZB.el(`<div class="msg user">${ZB.escapeHtml(m.text)}</div>`));
    else log.appendChild(ZB.el(`<div class="msg bot">${ZB.escapeHtml(m.text)}${m.meta?`<div class="meta">${ZB.escapeHtml(m.meta)}</div>`:""}</div>`));
  });
  log.scrollTop = log.scrollHeight;
}

async function send(text){
  if(!text.trim()) return;
  history.push({role:"user", text});
  paint();
  const typingEl = ZB.el(`<div class="msg bot"><div class="typing"><span></span><span></span><span></span></div></div>`);
  log.appendChild(typingEl); log.scrollTop = log.scrollHeight;
  try{
    const res = await ZB.api("/aurora/chat", {method:"POST", body:{
      message: text,
      account_id: null,
      customer_id: claims ? String(claims.sub) : null,
      session_id: sessionId
    }});
    typingEl.remove();
    history.push({role:"bot", text: res.response, meta: `${res.query_type||""}${res.result_count?` · ${res.result_count} record${res.result_count===1?"":"s"}`:""}`});
    paint();
  }catch(err){
    typingEl.remove();
    history.push({role:"bot", text: "Sorry, I couldn't reach Aurora just now — " + err.message});
    paint();
  }
}
document.querySelectorAll(".chip").forEach(c=>{ c.onclick = ()=> send(c.dataset.q); });
document.getElementById("chat-form").onsubmit = (e)=>{
  e.preventDefault();
  const input = e.target.querySelector("input");
  const val = input.value; input.value = "";
  send(val);
};
paint();
