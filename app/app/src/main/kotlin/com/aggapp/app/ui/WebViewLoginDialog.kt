package com.aggapp.app.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aggapp.core.model.Credential
import com.aggapp.platform.api.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 页面 console 序列化：object 参数 JSON.stringify，便于从 logcat 看到页面拿到的挑战数据 */
private const val CONSOLE_SERIALIZE_HOOK =
    """(function(){['log','info','warn','error'].forEach(function(m){var b=console[m];console[m]=function(){var a=[];for(var i=0;i<arguments.length;i++){var x=arguments[i];try{a.push(typeof x==='object'?JSON.stringify(x):String(x))}catch(e){a.push(String(x))}}b.apply(console,a)}})})()"""

/** dump 页面题干：取 #main 文本、压缩空白、去掉尾部按钮文字"确认"，供外置显示 */
private const val DOM_TEXT_DUMP =
    """(function(){var el=document.querySelector('#main')||document.body;var t=(el.innerText||'').replace(/\s+/g,' ').trim().replace(/确认$/,'').replace(/\\/g,'');return JSON.stringify(t.slice(0,300))})()"""

/** 外置确认：多选/九宫格题型下页面确认按钮可能不渲染，JS 直接触发 .btn-submit 的 click */
private const val EXTERNAL_CONFIRM_JS =
    """(function(){var b=document.querySelector('.btn-submit')||document.querySelector('[class*="submit" i]');if(!b)return JSON.stringify({r:'NO_BTN'});var cs=getComputedStyle(b);b.click();return JSON.stringify({r:'CLICKED',cls:b.className,display:cs.display,text:(b.innerText||'').slice(0,20)})})()"""

/**
 * 网络探针（诊断用）：包裹页面 fetch/XHR，把 URL/状态/响应片段经 console 落到 logcat。
 * 用于定位白屏类问题——PDD 验证页需先跑 vc_mid_render 握手（带 anti_content）才渲染 React 树，
 * 握手失败页面就停在空壳上。同时打印 navigator.userAgent，便于确认 WebView 是否被识别。
 */
private const val NET_HOOK =
    """(function(){if(window.__aggNetProbe)return;window.__aggNetProbe=1;
function log(s){try{console.log('[agg-net] '+s)}catch(e){}}
try{log('ua='+navigator.userAgent)}catch(e){}
var of=window.fetch;
if(of){window.fetch=function(){var a=arguments;var u=(a[0]&&a[0].url)||String(a[0]);
return of.apply(this,a).then(function(r){try{r.clone().text().then(function(t){log(u+' -> '+r.status+' '+String(t).slice(0,600))})}catch(e){};return r}).catch(function(e){log(u+' ERR '+e);throw e})}}
var oo=XMLHttpRequest.prototype.open,os=XMLHttpRequest.prototype.send;
XMLHttpRequest.prototype.open=function(m,u){this.__u=u;return oo.apply(this,arguments)};
XMLHttpRequest.prototype.send=function(){var x=this;x.addEventListener('loadend',function(){try{log(x.__u+' -> '+x.status+' '+String(x.responseText||'').slice(0,600))}catch(e){}});return os.apply(this,arguments)};
})()"""

/**
 * 渲染探针（诊断用）：页面加载 3 秒后执行一次，输出 <video>/<canvas>/<img> 元素状态、
 * 视频解码能力、body/#main 的计算样式（opacity/visibility/display）与几何尺寸，
 * 用于判定「DOM 有内容但画面纯白」是视频解码失败还是 CSS 隐藏遮罩。
 */
private const val STYLE_PROBE =
    """(function(){function log(s){try{console.log('[agg-style] '+s)}catch(e){}}
try{
var vids=document.querySelectorAll('video'),cvs=document.querySelectorAll('canvas');
log('videos='+vids.length+' canvases='+cvs.length+' imgs='+document.images.length);
var v0=document.createElement('video');
log('canPlay webm-vp8="'+v0.canPlayType('video/webm; codecs="vp8, vorbis"')+'" ogg-theora="'+v0.canPlayType('video/ogg; codecs="theora"')+'"');
for(var i=0;i<vids.length&&i<5;i++){var v=vids[i];var e=v.error;log('video'+i+' '+v.videoWidth+'x'+v.videoHeight+' ready='+v.readyState+' network='+v.networkState+' err='+(e?(e.code+':'+e.message):'null')+' src='+String(v.currentSrc||v.src||'').slice(0,120));}
for(var j=0;j<cvs.length&&j<5;j++){var c=cvs[j];log('canvas'+j+' '+c.width+'x'+c.height+' style='+c.offsetWidth+'x'+c.offsetHeight);}
var st=function(el){if(!el)return 'null';var s=getComputedStyle(el);var r=el.getBoundingClientRect();return 'op='+s.opacity+' vis='+s.visibility+' disp='+s.display+' zIndex='+s.zIndex+' rect='+Math.round(r.width)+'x'+Math.round(r.height)+'@('+Math.round(r.left)+','+Math.round(r.top)+') bg='+s.backgroundColor+' color='+s.color+' pos='+s.position;};
log('html '+st(document.documentElement));
log('body '+st(document.body));
var m=document.querySelector('#main');log('#main '+st(m));
log('win inner='+window.innerWidth+'x'+window.innerHeight+' scrollY='+window.pageYOffset+' dpr='+window.devicePixelRatio+' vvScale='+(window.visualViewport?window.visualViewport.scale:'-'));
// 修复尝试：html/body 高度塌陷(360x0)时撑回 100% 并滚回原点
try{document.documentElement.style.setProperty('height','100%','important');
document.body.style.setProperty('height','100%','important');
window.scrollTo(0,0);
var m2=document.querySelector('#main');
if(m2&&m2.getBoundingClientRect().top>window.innerHeight){m2.style.setProperty('top','0','important');}
log('after fix html '+st(document.documentElement)+' #main '+st(m2));
}catch(e){log('fix err '+e)}
var all=document.body?document.body.querySelectorAll('*').length:0;log('body childElements='+all);
// 定位白色遮罩：取视口中心点最顶层元素，向上回溯样式链
var cx=Math.floor(window.innerWidth/2),cy=Math.floor(window.innerHeight/2);
var hit=document.elementFromPoint(cx,cy);var chain=[];
while(hit&&chain.length<6){var s=getComputedStyle(hit);chain.push(hit.tagName+(hit.id?'#'+hit.id:'')+'.'+String(hit.className||'').slice(0,25)+' z='+s.zIndex+' bg='+s.backgroundColor+' op='+s.opacity+' rect='+Math.round(hit.getBoundingClientRect().width)+'x'+Math.round(hit.getBoundingClientRect().height));hit=hit.parentElement;}
log('hit@('+cx+','+cy+') = '+chain.join(' <- '));
// 反白遮罩：遍历 body 直接子元素，找覆盖全视口且背景接近纯白的 div，隐藏之
var killed=0;
try{var kids=document.body.children;
for(var k=0;k<kids.length;k++){var el=kids[k];if(el.id==='main')continue;
var s2=getComputedStyle(el);var r2=el.getBoundingClientRect();
var covers=r2.width>=window.innerWidth*0.9&&r2.height>=window.innerHeight*0.9;
var white=s2.backgroundColor==='rgb(255, 255, 255)'||s2.backgroundColor==='rgba(255, 255, 255, 1)'||s2.backgroundColor==='rgba(0, 0, 0, 0)'&&covers&&el!==m;
if(covers&&white&&el.querySelector&&!el.contains(m)){el.style.setProperty('display','none','important');killed++;}}
log('mask killed='+killed);
}catch(e){log('mask err '+e)}
var hit2=document.elementFromPoint(cx,cy);log('hit-after='+hit2.tagName+(hit2.id?'#'+hit2.id:''));
// 子元素级检查 + 强制显形：找出 #main 内被藏(visibility/opacity/display/transform)的元素并恢复
try{var m3=document.querySelector('#main');if(m3){var els=m3.querySelectorAll('*');var hidden=[],restored=0;
for(var q=0;q<els.length;q++){var e3=els[q];var s3=getComputedStyle(e3);
var isHidden=s3.visibility!=='visible'||parseFloat(s3.opacity)<0.15||s3.display==='none'||(s3.transform&&s3.transform!=='none'&&s3.transform.indexOf('matrix(1, 0, 0, 1, 0, 0)')<0&&s3.transform.indexOf('matrix(1,0,0,1,0,0)')<0);
if(isHidden){if(hidden.length<8)hidden.push(e3.tagName+'.'+String(e3.className||'').slice(0,20)+' vis='+s3.visibility+' op='+s3.opacity+' disp='+s3.display+' tf='+s3.transform.slice(0,40));
e3.style.setProperty('visibility','visible','important');e3.style.setProperty('opacity','1','important');e3.style.setProperty('transform','none','important');if(s3.display==='none')e3.style.setProperty('display','block','important');restored++;}}
log('#main els='+els.length+' hiddenList=['+hidden.join(' | ')+'] restored='+restored);
var hit3=document.elementFromPoint(cx,cy);log('hit-final='+hit3.tagName+(hit3.id?'#'+hit3.id:''));
// content-visibility 检查与修复：hidden 时内容不渲染不命中但样式照常，完美吻合白屏特征
var cv=function(el){if(!el)return 'null';var s=getComputedStyle(el);return 'cv='+s.contentVisibility+' contain='+s.contain+' rect='+Math.round(el.getBoundingClientRect().width)+'x'+Math.round(el.getBoundingClientRect().height);};
log('html '+cv(document.documentElement));
log('body '+cv(document.body));
var m4=document.querySelector('#main');log('#main '+cv(m4));
try{var fixed=0;var targets=[document.documentElement,document.body,m4];
for(var t=0;t<targets.length;t++){var el4=targets[t];if(!el4)continue;
if(getComputedStyle(el4).contentVisibility!=='visible'){el4.style.setProperty('content-visibility','visible','important');fixed++;}}
log('cv fixed='+fixed+' after #main '+cv(m4));
}catch(e){log('cv err '+e)}
// 样式表加载检查（排除 CSS 未加载导致布局塌陷）
try{var sheets=document.styleSheets;var info=[];
for(var si=0;si<sheets.length&&si<8;si++){var n=0;try{n=sheets[si].cssRules.length}catch(e2){n=-1}info.push(n+'r');}
log('styleSheets='+sheets.length+' rules=['+info.join(',')+'] links='+document.querySelectorAll('link[rel=stylesheet]').length);
}catch(e){log('sheet err '+e)}
// #main 子元素尺寸抽查：判断是否布局塌陷（全 0 尺寸）
try{if(m4){var kids4=m4.children;var sizes=[];
for(var ki=0;ki<kids4.length&&ki<10;ki++){var r4=kids4[ki].getBoundingClientRect();sizes.push(kids4[ki].tagName+':'+Math.round(r4.width)+'x'+Math.round(r4.height));}
log('#main children sizes=['+sizes.join(', ')+']');}}catch(e){log('size err '+e)}
}}catch(e){log('unhide err '+e)}
}catch(e){log('probe err '+e)}})()"""

/**
 * 核弹级显形：向页面注入 <style>，把 #main 子树全部强制可见、可命中、无变换裁剪，
 * 并把 html/body 高度撑满视口。用于对抗风控 SDK 对 WebView 的渲染层隐藏。
 */
private const val UNHIDE_HOOK =
    """(function(){
var css='#main, #main *{visibility:visible!important;opacity:1!important;content-visibility:visible!important;pointer-events:auto!important;clip-path:none!important;-webkit-clip-path:none!important}'+
'html,body{height:100%!important;overflow-y:auto!important}'+
'#main{position:static!important;top:0!important;left:0!important;transform:none!important;margin:0 auto!important;display:block!important}';
var st=document.getElementById('agg-unhide');
if(!st){st=document.createElement('style');st.id='agg-unhide';document.head.appendChild(st);}
st.textContent=css;
try{window.scrollTo(0,0)}catch(e){}
})()"""

/**
 * 通用 WebView 登录弹窗（拼多多短信登录首用）：
 * - 加载 [loginUrl]（平台登录页），用户在真实浏览器环境完成手机号+验证码/滑块；
 * - 轮询 CookieManager（HttpOnly cookie 也可读）+ onPageFinished 双路检测，把平铺
 *   cookie 解析成 map 交 [checkSuccess]（即 PlatformAdapter.detectLoginSuccess）判定；
 * - 命中 → 回调 [onCredential]（只回调一次）。
 * - [actionLabel]/[onAction]：可选手动兜底按钮（如 PDD 官方验证页过完但收尾接口未落 Cookie 时，
 *   由 App 端补发 auth/ticket）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewLoginDialog(
    title: String,
    loginUrl: String,
    checkSuccess: (cookies: Map<String, String>) -> Credential?,
    onCredential: (Credential) -> Unit,
    onDismiss: () -> Unit,
    tip: String? = "在页面中完成手机号验证码登录，成功后自动记录登录态",
    desktopUa: Boolean = false,
    clearCookies: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var done by remember { mutableStateOf(false) }
    var wv by remember { mutableStateOf<WebView?>(null) }
    // 页面题目文字外置显示：部分题型题干在 WebView 里渲染不出来，定时提取 innerText 展示到原生 UI
    var pageText by remember { mutableStateOf("") }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    DisposableEffect(Unit) { onDispose { scope.cancel() } }

    fun deliver(cred: Credential) {
        if (done) return
        done = true
        onCredential(cred)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title)
                Row {
                    // 外部浏览器兜底（2026-10-21）：PDD 验证页新增 vcMidRender 握手（anti_content），
                    // WebView 内 anti 生成失败会白屏；实测系统/桌面浏览器可正常渲染该页
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    TextButton(onClick = {
                        try {
                            ctx.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(loginUrl))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } catch (e: Exception) {
                            AppLog.e("AggApp", "open verify page in external browser failed", e)
                        }
                    }) { Text("系统浏览器打开") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
            // 题干外置（tip 为 null 的验证场景）：固定前缀小字 + 题目主体大字（截到「（单选/多选）」为止，选项列表不重复显示）
            if (tip == null && pageText.isNotBlank()) {
                val body = pageText
                    .replace(Regex("为了您的资金安全，请[继进][续行]安全验证"), "")
                    .trim()
                val question = Regex("请选择您.*?[（(][单多]选[）)]").find(body)?.value ?: body
                Text(
                    "为了您的资金安全，请进行安全验证",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    question,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 外置确认：多选/九宫格题型页面确认按钮可能不渲染，JS 直接触发 .btn-submit click
            androidx.compose.material3.OutlinedButton(
                onClick = {
                    wv?.evaluateJavascript(EXTERNAL_CONFIRM_JS) { result ->
                        AppLog.d("AggApp", "pdd external confirm: $result")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("选好了但页面没有确认按钮？点此外置确认") }
            // 手动收尾：验证在页面（或系统浏览器）里过完后，服务端已把该 vat 标记通过，
            // 但 WebView 未必收到收尾 Set-Cookie；由 App 端按同参补发 auth/ticket 落库
            if (actionLabel != null && onAction != null) {
                androidx.compose.material3.Button(
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(actionLabel) }
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(if (desktopUa) 640.dp else 520.dp),
            factory = { ctx ->
                val cookieManager = CookieManager.getInstance()
                if (clearCookies) {
                    // 清掉残留 cookie（如上次验证种下的失效 PDDAccessToken），否则弹窗打开即误判"已登录"秒退
                    cookieManager.removeAllCookies(null)
                    cookieManager.flush()
                    AppLog.d("AggApp", "pdd verify cookies cleared before load")
                }
                val webView = WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    if (desktopUa) {
                        // login.html 检测移动端 WebView 环境会跳 pinduoduo:// deep link（ERR_UNKNOWN_URL_SCHEME），
                        // 伪装 PC Chrome 后页面正常渲染（扫码登录 Tab 完整可用，已 PC Chrome 实测）
                        settings.userAgentString =
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
                        // 桌面页面按自身 viewport 整体缩放渲染；否则窄视口下确认按钮等元素渲染不出来
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                    } else {
                        // 2026-10-21 白屏定位：Android WebView 默认 UA 带 "; wv" 标记，PDD 风控 SDK
                        // 识别到 WebView 环境后 vc_mid_render 握手失败（anti_content 不被服务端接受），
                        // 页面 React 树不再渲染 → 只剩空白壳（日志特征：console 有 SDK dump、dom 文本为空、
                        // 只有 api_uid cookie）。去掉 wv 标记伪装成移动端 Chrome。
                        settings.userAgentString = settings.userAgentString.replace("; wv", "")
                        settings.useWideViewPort = true
                        // PDD 挑战疑似视频/Canvas 渲染，允许自动播放避免画面空白
                        settings.mediaPlaybackRequiresUserGesture = false
                    }
                    WebView.setWebContentsDebuggingEnabled(true) // CDP 远程调试抓页面网络请求

                    fun readCookies(): Map<String, String> =
                        cookieManager.getCookie(loginUrl)?.split("; ")
                            ?.mapNotNull { pair ->
                                val eq = pair.indexOf('=')
                                if (eq > 0) pair.substring(0, eq) to pair.substring(eq + 1) else null
                            }?.toMap() ?: emptyMap()

                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                        ): Boolean {
                            val u = request.url
                            // 拦截 pinduoduo:// 等非 http(s) 跳转（deep link 兜底），避免整页报错
                            if (u.scheme != "http" && u.scheme != "https") {
                                AppLog.d("AggApp", "pdd verify block scheme jump: $u")
                                return true
                            }
                            return false
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            // 先装网络探针，再序列化 console 对象参数（页面日志 [object Object] → JSON 文本）
                            view.evaluateJavascript(NET_HOOK, null)
                            view.evaluateJavascript(CONSOLE_SERIALIZE_HOOK, null)
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            AppLog.d("AggApp", "pdd verify page finished: $url")
                            checkSuccess(readCookies())?.let(::deliver)
                            // 渲染探针：3s 后查 video/canvas/计算样式，判定白屏根因
                            scope.launch {
                                delay(3000)
                                view.evaluateJavascript(STYLE_PROBE) {
                                    // 探针（含白遮罩清理）跑完后再截一张图，对比修复效果
                                    captureWebViewToFile(view, 90)
                                }
                            }
                            // 核弹级显形：注入 <style> 强制 #main 子树全部可见可命中；
                            // 页面 SDK 若用 MutationObserver 重新隐藏，每 1.5s 重注一次（共 10 次）
                            scope.launch {
                                val unhide = UNHIDE_HOOK
                                for (round in 1..10) {
                                    view.evaluateJavascript(unhide, null)
                                    delay(1500)
                                }
                            }
                            // 持续 dump 页面可见文本（30 次 × 2s），文本变化才记录，覆盖用户操作期间的状态变化
                            scope.launch {
                                var lastText = ""
                                for (i in 1..30) {
                                    delay(2000)
                                    view.evaluateJavascript(DOM_TEXT_DUMP) { text ->
                                        if (text != lastText) {
                                            lastText = text
                                            AppLog.d("AggApp", "pdd page dom[$i]: $text")
                                            pageText = text?.removeSurrounding("\"")?.take(400) ?: ""
                                            // 文本变化时同步截一张 WebView 画面存相册「AggApp」，用于定位
                                            // "DOM 有内容但视觉空白" 的问题（如视频/Canvas 挑战解码失败）
                                            captureWebViewToFile(view, i)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                            AppLog.d(
                                "AggApp",
                                "pdd console[${consoleMessage.messageLevel()}] ${consoleMessage.message()} " +
                                    "@${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}",
                            )
                            return true
                        }

                        override fun onReceivedTitle(view: WebView, title: String?) {
                            AppLog.d("AggApp", "pdd verify page title: $title")
                        }
                    }
                    // 2026-10-21 白屏攻坚：DOM 完整/布局正常但渲染层全白（view.draw 白、elementFromPoint 落空），
                    // 疑似该设备 WebView 硬件合成器在 Dialog 内渲染失效——强制软件渲染排除合成器因素
                    setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                    loadUrl(loginUrl)
                }
                // 聚焦修复：Dialog 内 WebView 可能不持有焦点，页面 SDK 依赖 window focus/blur 事件控制显隐
                webView.requestFocus()
                scope.launch {
                    delay(500)
                    webView.evaluateJavascript("try{window.focus();window.dispatchEvent(new Event('focus'));document.dispatchEvent(new Event('visibilitychange'))}catch(e){}", null)
                }
                // 轮询兜底：部分 SPA 登录成功不触发新的 onPageFinished；cookie 集变化时打日志便于诊断
                var lastCookieKeys = ""
                scope.launch {
                    while (isActive) {
                        delay(1000)
                        val cookies = withContext(Dispatchers.IO) {
                            CookieManager.getInstance().getCookie(loginUrl)
                        }?.split("; ")?.mapNotNull { pair ->
                            val eq = pair.indexOf('=')
                            if (eq > 0) pair.substring(0, eq) to pair.substring(eq + 1) else null
                        }?.toMap() ?: emptyMap()
                        val keys = cookies.keys.sorted().toString()
                        if (keys != lastCookieKeys) {
                            lastCookieKeys = keys
                            AppLog.d("AggApp", "pdd verify cookies: $keys")
                        }
                        checkSuccess(cookies)?.let(::deliver)
                    }
                }
                webView
            },
            update = { wv = it },
        )
    }
}

/** 把 WebView 当前画面存入相册「AggApp」目录，日志里输出文件名，供用户查看/回传定位渲染问题 */
private fun captureWebViewToFile(view: android.webkit.WebView, seq: Int) {
    runCatching {
        val w = view.width.coerceAtLeast(1)
        val h = view.height.coerceAtLeast(1)
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        val name = "pdd_verify_$seq.png"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AggApp")
        }
        val uri = view.context.contentResolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
        ) ?: return
        view.context.contentResolver.openOutputStream(uri)?.use { os ->
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os)
        }
        bmp.recycle()
        AppLog.d("AggApp", "pdd webview snapshot saved: Pictures/AggApp/$name")
    }.onFailure {
        AppLog.e("AggApp", "pdd webview snapshot failed", it)
    }
}
