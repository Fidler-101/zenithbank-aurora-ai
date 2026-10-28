/* If we already have a session, skip straight to the dashboard app. */
if(ZB.getToken()){ window.location.href = "../banking/index.html"; }

function renderAuthCard(mode, ctx={}){
  const mount = document.getElementById("auth-card");
  mount.innerHTML = "";
  if(mode==="login") mount.appendChild(loginForm());
  else if(mode==="register") mount.appendChild(registerForm());
  else if(mode==="otp") mount.appendChild(otpForm(ctx));
  else if(mode==="pending") mount.appendChild(pendingCard(ctx));
}
function tabs(active, onLogin, onRegister){
  const wrap = ZB.el(`
    <div class="auth-tabs">
      <button class="auth-tab ${active==='login'?'active':''}" data-t="login">Log in</button>
      <button class="auth-tab ${active==='register'?'active':''}" data-t="register">Open an account</button>
    </div>`);
  wrap.querySelector('[data-t="login"]').onclick = onLogin;
  wrap.querySelector('[data-t="register"]').onclick = onRegister;
  return wrap;
}
function setLoading(btn, loading, labelWhenDone){
  const label = btn.querySelector(".btn-label");
  if(loading){ btn.disabled = true; label.innerHTML = `<i class="fa-solid fa-circle-notch spin"></i> Working…`; }
  else{ btn.disabled = false; label.textContent = labelWhenDone; }
}

function loginForm(){
  const card = document.createElement("div");
  card.appendChild(tabs("login", ()=>{}, ()=>renderAuthCard("register")));
  const form = ZB.el(`
    <form id="login-form">
      <div class="field"><label>Email address</label><input type="email" name="email" placeholder="you@example.com" required></div>
      <div class="field"><label>Password</label><input type="password" name="password" placeholder="••••••••" required></div>
      <button type="submit" class="btn btn-primary btn-block"><span class="btn-label">Log in</span></button>
      <p style="text-align:center;margin:16px 0 0;font-size:12.5px;color:var(--muted)">
        New to ZenithBank? <button type="button" class="link-btn" id="go-register">Open an account</button>
      </p>
    </form>
  `);
  form.querySelector("#go-register").onclick = ()=>renderAuthCard("register");
  form.onsubmit = async (e)=>{
    e.preventDefault();
    const fd = new FormData(form);
    const btn = form.querySelector("button[type=submit]");
    setLoading(btn, true);
    try{
      const res = await ZB.api("/customers/login", {method:"POST", auth:false, body:{
        email: fd.get("email"), password: fd.get("password")
      }});
      if(!res.success) throw new Error(res.message || "Login failed.");
      ZB.setToken(res.token);
      ZB.toast("Welcome back.", "success");
      window.location.href = "../banking/index.html";
    }catch(err){
      ZB.toast(err.message, "error");
      setLoading(btn, false, "Log in");
    }
  };
  card.appendChild(form);
  return card;
}

function registerForm(){
  const card = document.createElement("div");
  card.appendChild(tabs("register", ()=>renderAuthCard("login"), ()=>{}));
  const form = ZB.el(`
    <form id="register-form" novalidate>
      <div class="fieldset-title">Personal details</div>
      <div class="row-2">
        <div class="field" data-f="firstName"><label>First name</label><input name="firstName" required></div>
        <div class="field" data-f="lastName"><label>Last name</label><input name="lastName" required></div>
      </div>
      <div class="field" data-f="idNumber"><label>SA ID number</label><input name="idNumber" maxlength="13" placeholder="13 digits" required></div>
      <div class="row-2">
        <div class="field" data-f="dateOfBirth"><label>Date of birth</label><input type="date" name="dateOfBirth" required></div>
        <div class="field" data-f="phoneNumber"><label>Phone number</label><input name="phoneNumber" placeholder="0821234567" required></div>
      </div>
      <div class="fieldset-title">Address</div>
      <div class="field" data-f="addressLine1"><label>Address line 1</label><input name="addressLine1" required></div>
      <div class="field"><label>Address line 2 (optional)</label><input name="addressLine2"></div>
      <div class="row-3">
        <div class="field" data-f="city"><label>City</label><input name="city" required></div>
        <div class="field" data-f="province"><label>Province</label><input name="province" required></div>
        <div class="field" data-f="postalCode"><label>Postal code</label><input name="postalCode" maxlength="4" required></div>
      </div>
      <div class="fieldset-title">Account security</div>
      <div class="field" data-f="email"><label>Email address</label><input type="email" name="email" required></div>
      <div class="row-2">
        <div class="field" data-f="password"><label>Password</label><input type="password" name="password" required></div>
        <div class="field" data-f="confirmPassword"><label>Confirm password</label><input type="password" name="confirmPassword" required></div>
      </div>
      <button type="submit" class="btn btn-accent btn-block" style="margin-top:6px"><span class="btn-label">Create account</span></button>
      <p style="text-align:center;margin:16px 0 0;font-size:12.5px;color:var(--muted)">
        Already have an account? <button type="button" class="link-btn" id="go-login">Log in</button>
      </p>
    </form>
  `);
  form.querySelector("#go-login").onclick = ()=>renderAuthCard("login");
  form.onsubmit = async (e)=>{
    e.preventDefault();
    const fd = new FormData(form);
    const data = Object.fromEntries(fd.entries());
    const errs = validateRegistration(data);
    form.querySelectorAll(".field.invalid").forEach(f=>f.classList.remove("invalid"));
    if(Object.keys(errs).length){
      Object.keys(errs).forEach(name=>{
        const f = form.querySelector(`[data-f="${name}"]`);
        if(f) f.classList.add("invalid");
      });
      ZB.toast("Check the highlighted fields.", "error");
      return;
    }
    const btn = form.querySelector("button[type=submit]");
    setLoading(btn, true);
    try{
      const res = await ZB.api("/customers/register", {method:"POST", auth:false, body:data});
      if(!res.success) throw new Error(res.message || "Registration failed.");
      ZB.toast("Account created. Check your email for an OTP.", "success");
      renderAuthCard("otp", {email: res.email});
    }catch(err){
      ZB.toast(err.message, "error");
      setLoading(btn, false, "Create account");
    }
  };
  card.appendChild(form);
  return card;
}
function validateRegistration(d){
  const errs = {};
  if(!d.firstName || d.firstName.trim().length < 2) errs.firstName = 1;
  if(!d.lastName || d.lastName.trim().length < 2) errs.lastName = 1;
  if(!/^\d{13}$/.test(d.idNumber||"")) errs.idNumber = 1;
  if(!d.dateOfBirth) errs.dateOfBirth = 1;
  if(!/^(\+27|0)[6-8][0-9]{8}$/.test(d.phoneNumber||"")) errs.phoneNumber = 1;
  if(!d.addressLine1) errs.addressLine1 = 1;
  if(!d.city) errs.city = 1;
  if(!d.province) errs.province = 1;
  if(!/^\d{4}$/.test(d.postalCode||"")) errs.postalCode = 1;
  if(!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(d.email||"")) errs.email = 1;
  if(!/^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&])[A-Za-z\d@$!%*?&]{8,}$/.test(d.password||"")) errs.password = 1;
  if(d.confirmPassword !== d.password) errs.confirmPassword = 1;
  return errs;
}

function otpForm(ctx){
  const card = document.createElement("div");
  card.innerHTML = `
    <div style="text-align:center;margin-bottom:22px;">
      <div class="status-icon pending"><i class="fa-solid fa-envelope-open-text"></i></div>
      <h3 style="margin:0 0 4px;">Verify your email</h3>
      <p style="font-size:13px;color:var(--muted);margin:0;">Enter the 6-digit code sent to<br><strong>${ZB.escapeHtml(ctx.email||"")}</strong></p>
    </div>`;
  const form = ZB.el(`
    <form id="otp-form">
      <div class="otp-boxes">${Array.from({length:6}).map((_,i)=>`<input maxlength="1" inputmode="numeric" data-i="${i}">`).join("")}</div>
      <button type="submit" class="btn btn-accent btn-block" style="margin-top:16px;"><span class="btn-label">Verify code</span></button>
      <p style="text-align:center;margin:16px 0 0;font-size:12.5px;color:var(--muted)">
        Didn't get it? <button type="button" class="link-btn" id="resend">Resend code</button>
      </p>
    </form>`);
  const boxes = [...form.querySelectorAll(".otp-boxes input")];
  boxes.forEach((b,i)=>{
    b.oninput = ()=>{ if(b.value && boxes[i+1]) boxes[i+1].focus(); };
    b.onkeydown = (e)=>{ if(e.key==="Backspace" && !b.value && boxes[i-1]) boxes[i-1].focus(); };
  });
  form.querySelector("#resend").onclick = async ()=>{
    try{ await ZB.api("/customers/resend-otp", {method:"POST", auth:false, body:{email: ctx.email}}); ZB.toast("A new code is on its way.", "success"); }
    catch(err){ ZB.toast(err.message, "error"); }
  };
  form.onsubmit = async (e)=>{
    e.preventDefault();
    const code = boxes.map(b=>b.value).join("");
    if(code.length !== 6){ ZB.toast("Enter all 6 digits.", "error"); return; }
    const btn = form.querySelector("button[type=submit]");
    setLoading(btn, true);
    try{
      const res = await ZB.api("/customers/verify-otp", {method:"POST", auth:false, body:{email: ctx.email, otpCode: code}});
      if(!res.success) throw new Error(res.message || "Verification failed.");
      renderAuthCard("pending", {email: ctx.email});
    }catch(err){
      ZB.toast(err.message, "error");
      setLoading(btn, false, "Verify code");
    }
  };
  card.appendChild(form);
  return card;
}
function pendingCard(ctx){
  const card = ZB.el(`
    <div class="status-card">
      <div class="status-icon success"><i class="fa-solid fa-circle-check"></i></div>
      <h3 style="margin:0 0 8px;">Email verified</h3>
      <p style="font-size:13px;color:var(--muted);line-height:1.6;margin:0 0 22px;">
        Your account is now waiting on admin approval — part of ZenithBank's KYC process.
        You'll be notified at <strong>${ZB.escapeHtml(ctx.email||"your email")}</strong> once it's approved.
      </p>
      <button class="btn btn-ghost btn-block" id="back-to-login">Back to log in</button>
    </div>`);
  card.querySelector("#back-to-login").onclick = ()=>renderAuthCard("login");
  return card;
}

renderAuthCard("login");
