// SF identity code - full YYB-free chain E2E test
// Chain: suuid -> MIMP cookie mint -> createIdentityCode -> ACS code
const crypto = require('crypto');
const md5 = (s) => crypto.createHash('md5').update(s).digest('hex');
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36';
const SUUID = '6923f190cd9f44458653b9c9e5ef7df2';
const MIMP_SECRET = 'wwesldfs29aniversaryvdld29';

function assertSig(ts, expected) {
  const calc = md5(`token=${MIMP_SECRET}&timestamp=${ts}&sysCode=MCS-MIMP-CORE`);
  console.log(`[sig] calc=${calc} captured=${expected} match=${calc === expected}`);
}

async function mintCookies() {
  const biz = encodeURIComponent(JSON.stringify({ path: '/campusZoneId', supportShare: 'YES', identifySucc: '2', from: 'xszx03' }));
  let url = `https://ucmp.sf-express.com/wechat-act3/weixin/activity/sfnewactivity?bizCode=${biz}`;
  let cookies = {};
  for (let hop = 0; hop < 8; hop++) {
    const ch = Object.entries(cookies).map(([k, v]) => `${k}=${v}`).join('; ');
    const res = await fetch(url, { redirect: 'manual', headers: { 'User-Agent': UA, suuid: SUUID, ...(ch ? { Cookie: ch } : {}) } });
    for (const sc of res.headers.getSetCookie?.() || []) {
      const [kv] = sc.split(';'); const eq = kv.indexOf('=');
      cookies[kv.slice(0, eq).trim()] = kv.slice(eq + 1).trim();
    }
    const loc = res.headers.get('location');
    if (loc) { url = new URL(loc, url).href; continue; }
    break;
  }
  return cookies;
}

async function createIdentityCode(cookieHeader) {
  const ts = Date.now();
  const sig = md5(`token=${MIMP_SECRET}&timestamp=${ts}&sysCode=MCS-MIMP-CORE`);
  const res = await fetch('https://mcs-mimp-web.sf-express.com/mcs-mimp/commonPost/~memberManage~studentService~createIdentityCode', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json', Accept: 'application/json, text/plain, */*',
      timestamp: String(ts), signature: sig, sysCode: 'MCS-MIMP-CORE',
      channel: 'xszx03', platform: 'MINI_PROGRAM',
      'User-Agent': UA,
      Origin: 'https://mcs-mimp-web.sf-express.com',
      Referer: 'https://mcs-mimp-web.sf-express.com/origin/a/mimp-member-right/campusZoneId',
      Cookie: cookieHeader,
    },
    body: '{}',
  });
  return { status: res.status, json: await res.json().catch(() => null) };
}

(async () => {
  // verify signature algo against user's captured values first
  assertSig(1790614783453, '42a66c823c033d660db68380b37ec3c3');

  // A) use the cookies captured from user's phone directly
  const captured = 'JSESSIONID=B4F1F80536C3D2349B49D3602C8412D4; _login_user_id_=D0AC6DE1BDC14953AC20A7FAD0FCE036; _login_mobile_=13800000000; sessionId=B4F1F80536C3D2349B49D3602C8412D4';
  const r1 = await createIdentityCode(captured);
  console.log('[A captured-cookie]', r1.status, JSON.stringify(r1.json));

  // B) full chain: mint cookies from suuid ourselves
  const cookies = await mintCookies();
  const ch = Object.entries(cookies).map(([k, v]) => `${k}=${v}`).join('; ');
  console.log('[B minted cookies]', Object.keys(cookies).join(','));
  if (cookies.sessionId || cookies.JSESSIONID) {
    const r2 = await createIdentityCode(ch);
    console.log('[B full-chain]', r2.status, JSON.stringify(r2.json));
  }
})();
