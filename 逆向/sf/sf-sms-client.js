/**
 * 顺丰速运小程序（wxd4185d00bf7e08ac）短信验证码登录 Node 复现
 * 目标：脱离 YYB 服务，手机号+短信码直接拿登录态（suuid）
 *
 * 链路（2026-09-29 抓包确认，签名算法已对拍 100% 吻合）：
 *   ① 发码  POST /wxopen/login/code?mobile=<mobile>      签名三头（+极验四件套? 待实测）
 *   ② 登录  POST /wxopen/weixin/bindMember?mobile=..&code=..&isSynAddress=check&sign=&regSource=WXAPP
 *          （无签名头）→ suuid 即登录态
 *   ③ 验证  POST /wxopen/weixin/wxMemIsBind              签名三头 + suuid
 *
 * 用法：
 *   node sf-sms-client.js send  <手机号> [gtLotNumber gtCaptchaOutput gtPassToken gtGenTime]
 *   node sf-sms-client.js login <手机号> <短信码> [suuid]
 *   node sf-sms-client.js check [suuid]
 * suuid 缺省从 sf-sms-session.json 读取；首次全流程：send（无参试风控）→ login → check
 */

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const HOST = 'https://ucmp.sf-express.com';
const SESSION_FILE = path.join(__dirname, 'sf', 'sf-sms-session.json');

// config/sign.js 解混淆结果（commonSignConf，pro）
const SIGN_CONF = { appId: 'wxapp-valid-0328', key: '2b08f7f6bf564a1dada1570535fd44ba' };
const WXAPP_VERSION = 'V17.78';

function randomWord(len) {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  let s = '';
  for (let i = 0; i < len; i++) s += chars[crypto.randomInt(chars.length)];
  return s;
}

/** 签名三头。requestBody=JSON.stringify(data)，无 data 时空串（签名串则省略 requestBody 段） */
function makeSignHeaders(data) {
  const nonceStr = randomWord(32);
  const rb = data === undefined || data === null ? '' : JSON.stringify(data);
  const plain = rb
    ? `appId=${SIGN_CONF.appId}&nonceStr=${nonceStr}&requestBody=${rb}&key=${SIGN_CONF.key}`
    : `appId=${SIGN_CONF.appId}&nonceStr=${nonceStr}&key=${SIGN_CONF.key}`;
  const sign = crypto.createHash('md5').update(plain).digest('hex').toUpperCase();
  return { appId: SIGN_CONF.appId, sign, nonceStr };
}

function baseHeaders(extra) {
  return {
    Accept: 'application/json',
    'Content-Type': 'application/json',
    'wxapp-version': WXAPP_VERSION,
    envVersion: 'release',
    msgid: 'WA' + crypto.randomUUID().replace(/-/g, '').toUpperCase(),
    deviceId: '1768372166828-209674-0add5a918fbe01-19190580', // 设备ID 格式：ts-rand-hex-rand
    'grey-mobile-rem': String(crypto.randomInt(10)),
    bno: '',
    ...extra,
  };
}

async function request(url, { headers, body = {} } = {}) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { ...headers, 'Content-Length': Buffer.byteLength(JSON.stringify(body)) },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch { json = { raw: text }; }
  return { status: res.status, json, setCookie: res.headers.getSetCookie?.() || [] };
}

function loadSession() {
  try { return JSON.parse(fs.readFileSync(SESSION_FILE, 'utf-8')); } catch { return {}; }
}
function saveSession(s) {
  fs.mkdirSync(path.dirname(SESSION_FILE), { recursive: true });
  fs.writeFileSync(SESSION_FILE, JSON.stringify(s, null, 2));
  console.log('[session] saved ->', SESSION_FILE);
}

/** ① 发码。gt 四件套可选（若服务端强制极验则必传，值来源：小程序抓包或人工过验证码） */
async function sendCode(mobile, gt) {
  const headers = baseHeaders({ ...makeSignHeaders({}), ...(gt || {}) });
  if (gt) headers['gt-scene'] = 'WXAPP-LOGIN';
  const r = await request(`${HOST}/wxopen/login/code?mobile=${mobile}`, { headers });
  console.log('[sendCode]', r.status, JSON.stringify(r.json));
  return r;
}

/** ② 登录（无签名头，plain） */
async function bindMember(mobile, code, suuid) {
  const headers = baseHeaders(suuid ? { suuid } : {});
  const url = `${HOST}/wxopen/weixin/bindMember?mobile=${mobile}&code=${code}&isSynAddress=check&sign=&regSource=WXAPP`;
  const r = await request(url, { headers });
  console.log('[bindMember]', r.status, JSON.stringify(r.json, null, 2));
  return r;
}

/** ③ 登录态校验（签名三头 + suuid） */
async function wxMemIsBind(suuid) {
  const headers = baseHeaders({ ...makeSignHeaders({}), suuid });
  const r = await request(`${HOST}/wxopen/weixin/wxMemIsBind`, { headers });
  console.log('[wxMemIsBind]', r.status, JSON.stringify(r.json, null, 2));
  return r;
}

/** 匿名 session 尝试：不带 suuid 直接跑（待实测服务端是否强制） */
async function tryGetAnonymousSession() {
  // TODO: 若发码/登录强制要求 suuid，此处需要探索免 wx.login 的 session 来源
  console.log('[warn] Node 无法调 wx.login，无匿名 sessionId。先试无 suuid 直连。');
}

(async () => {
  const [cmd, ...args] = process.argv.slice(2);
  const session = loadSession();
  if (cmd === 'send') {
    const [mobile, ...gt] = args;
    const gtHeaders = gt.length === 4
      ? { 'gt-lot-number': gt[0], 'gt-captcha-output': gt[1], 'gt-pass-token': gt[2], 'gt-gen-time': gt[3] }
      : undefined;
    if (gtHeaders) console.log('[sendCode] with geetest headers');
    else console.log('[sendCode] WITHOUT geetest headers (risk-control probe)');
    await sendCode(mobile, gtHeaders);
  } else if (cmd === 'login') {
    const [mobile, code, suuidArg] = args;
    const suuid = suuidArg || session.suuid;
    const r = await bindMember(mobile, code, suuid);
    if (r.json?.success) {
      // 登录态 = suuid（不变）。记录会员信息
      saveSession({ ...session, suuid: suuid || null, mobile, memNo: r.json.obj?.userBound?.memno, boundAt: Date.now() });
    }
  } else if (cmd === 'check') {
    const suuid = args[0] || session.suuid;
    if (!suuid) return console.log('no suuid');
    const r = await wxMemIsBind(suuid);
    saveSession({ ...session, suuid, alive: !!r.json?.success, checkedAt: Date.now() });
  } else {
    console.log('用法: node sf-sms-client.js send|login|check ...');
  }
})();
