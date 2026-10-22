package com.aggapp.platform.pdd

import com.aggapp.core.model.Credential
import com.aggapp.core.model.Platform
import com.aggapp.core.model.PlatformError
import com.aggapp.platform.api.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Base64

/**
 * 拼多多扫码登录客户端（2026-09-29 Node 全链逆向实测，pdd/test_verify.js 同源移植）。
 *
 * 链路：genQr 生成 AUTH_LOGIN 二维码（拼多多 App 相册识别扫码）→ checkQr 轮询
 * （60022 无更新 / 60020 过期 / ticket 已确认）→ authTicket 换登录态
 * （成功 Set-Cookie PDDAccessToken；error_code 54002 = 强制二次验证，下发 verify_auth_token）
 * → vcPreCk（RSA 密钥交换 + AES 加密参数）→ obtainCaptcha（服务端下发验证类型，
 * 1/2/3=短信 Text，22-25 滑块、41 拼图、61 手势、91 窗帘等无法自动化）
 * → Text：userVerify 输码 → authTicket(status:1) 出 Cookie。
 *
 * 加密（对齐页面 bundle 模块 14419/45328/63132）：
 * - 每次验证会话随机生成 16 字符 AES key/iv（字符集 A-Za-z0-9+/，即页面 EY.a/EY.v），
 *   RSA-2048 PKCS#1 加密后放 vcPreCk body 的 a/v 字段完成密钥交换；
 * - 后续验证请求体 {e:true, verify_auth_token(明文), content:AES-CBC-PKCS5(其余参数 JSON)};
 * - 响应 {e:true, content} 用同 key/iv 解密。
 */
@Singleton
class PddQrClient @Inject constructor(
    private val antiContent: PddAntiContent,
) {

    /** 本次验证会话的 AES key/iv（页面 EY.a/EY.v 对应物），vcPreCk 时生成 */
    private var sessionKey: Pair<ByteArray, ByteArray>? = null

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .addInterceptor(com.aggapp.platform.api.NetDebug.interceptor)
            .build()
    }

    // ---------- 数据 ----------

    data class QrInfo(val secretKey: String, val qrContent: String)

    sealed class CheckResult {
        object Waiting : CheckResult()
        object Expired : CheckResult()
        data class Ticket(val ticket: String) : CheckResult()
    }

    sealed class AuthResult {
        data class Ok(val credential: Credential) : AuthResult()
        data class NeedVerify(val verifyAuthToken: String) : AuthResult()
    }

    data class PreCk(val serverTime: Long?, val salt: String)

    /** type=101 知识验证题（challenge_captcha 下发，原生弹窗作答） */
    data class QuizQuestion(
        val questionId: Int,
        val desc: String,
        val style: String,
        val options: Map<String, String>, // key → 显示文本或图片 URL
        val correctAnswerCnt: Int = 1,    // 需选中的答案数：1=单选，>1=多选
        val questionOrder: Int = 0,       // 服务端 question_order（第几题）
    )

    // ---------- 扫码 ----------

    /** 生成登录二维码。qrContent 需渲染成二维码供拼多多 App 相册识别 */
    suspend fun genQr(): QrInfo {
        val ac = anti()
        val body = JSONObject().apply {
            put("pdd_biz_scene", SCENE)
            put("anti_content", ac)
            put("scene_data", JSONObject().put("app_id", QR_APP_ID))
        }
        val resp = post("api/cupid/query_qr_code", body, ac)
        val env = parseJson(resp) ?: fail("query_qr_code 响应非 JSON：${resp.body.take(120)}")
        val secretKey = env.optString("secret_key")
        val qrContent = env.optString("data")
        if (secretKey.isBlank() || qrContent.isBlank()) fail("query_qr_code 缺 secret_key/data：$env")
        AppLog.d(TAG, "genQr ok secret_key=$secretKey qr=${qrContent.take(80)}")
        return QrInfo(secretKey, qrContent)
    }

    /** 轮询扫码状态：3s 一次；60022=无更新，60020=过期（上层自动重新 genQr） */
    suspend fun checkQr(secretKey: String): CheckResult {
        val ac = anti()
        val body = JSONObject()
            .put("secret_key", secretKey)
            .put("anti_content", ac)
            .put("pdd_biz_scene", SCENE)
        val resp = post("api/cupid/login/check_qr_code", body, ac)
        val env = parseJson(resp) ?: return CheckResult.Waiting
        return when {
            env.optString("ticket").isNotBlank() -> {
                AppLog.d(TAG, "checkQr 拿到 ticket=${env.optString("ticket").take(24)}...")
                CheckResult.Ticket(env.optString("ticket"))
            }
            env.optString("error_code") == "60020" -> {
                AppLog.d(TAG, "checkQr 60020 二维码过期")
                CheckResult.Expired
            }
            else -> CheckResult.Waiting
        }
    }

    /**
     * ticket 换登录态。成功 → Ok(Credential)；54002 → NeedVerify(verify_auth_token)；
     * [vat]/[status] 为二次验证通过后的重发参数（页面 bundle：verifyLogin 合并
     * {verify_auth_token, add_cookie, secret_key, ticket, anti_content, status:1}）。
     */
    suspend fun authTicket(
        ticket: String,
        secretKey: String,
        vat: String? = null,
        status: Int? = null,
    ): AuthResult {
        val ac = anti()
        val body = JSONObject()
            .put("ticket", ticket)
            .put("secret_key", secretKey)
            .put("add_cookie", true)
            .put("anti_content", ac)
        vat?.let { body.put("verify_auth_token", it) }
        status?.let { body.put("status", it) }
        val resp = post("api/sigerus/auth/ticket", body, ac)
        val env = parseJson(resp)
        AppLog.d(TAG, "authTicket(vat=${vat != null},status=$status) http=${resp.body.take(300)} cookies=${parseCookieMap(resp.setCookies).keys}")
        // 成功判定：Set-Cookie 出现 PDDAccessToken（与 WebView detectLoginSuccess 同源）
        val cookies = parseCookieMap(resp.setCookies)
        val token = cookies["PDDAccessToken"]
        if (!token.isNullOrBlank()) {
            val extra = buildMap {
                cookies["pdd_user_id"]?.let { put("pdd_user_id", it) }
                cookies["pdd_user_uin"]?.let { put("pdd_uin", it) }
            }
            return AuthResult.Ok(Credential(Platform.PDD, token, extra, System.currentTimeMillis()))
        }
        val errCode = env?.optString("error_code").orEmpty()
        if (errCode == "54002") {
            val vatNew = env?.optString("verify_auth_token").orEmpty()
            if (vatNew.isNotBlank()) return AuthResult.NeedVerify(vatNew)
        }
        fail("auth/ticket 失败：${env?.optString("error_msg")} ($errCode) ${resp.body.take(500)}")
    }

    // ---------- 二次验证（Text 短信码；加密链路对齐页面 psnl_verification bundle）----------

    /**
     * 验证预检：生成会话 AES key/iv → RSA 密钥交换（body a/v 字段）+ 加密参数
     * {anti_content, sdk_type:1, client_time, verify_auth_token}。
     * 响应解密得 {server_time, salt, success}。
     */
    suspend fun vcPreCk(vat: String): PreCk {
        val ac = anti()
        val (key, iv) = randomKeyIv().also { sessionKey = it }
        val params = JSONObject()
            .put("anti_content", ac)
            .put("sdk_type", 1)
            .put("client_time", System.currentTimeMillis())
        val body = JSONObject()
            .put("e", true)
            .put("verify_auth_token", vat) // 明文（页面 encryptParams 排除项）
            .put("content", aesEncrypt(params.toString(), key, iv))
            .put("a", rsaEncryptB64(String(key, Charsets.UTF_8)))
            .put("v", rsaEncryptB64(String(iv, Charsets.UTF_8)))
        val resp = post("api/phantom/vc_pre_ck", body, ac)
        val outer = parseJson(resp) ?: fail("vc_pre_ck 响应非 JSON：${resp.body.take(120)}")
        val data = decryptResponse(outer, key, iv) ?: fail("vc_pre_ck 解密失败：$outer")
        AppLog.d(TAG, "vcPreCk resp=${data.toString().take(200)}")
        return PreCk(
            serverTime = data.optLong("server_time").takeIf { it > 0 },
            salt = data.optString("salt"),
        )
    }

    /**
     * 获取验证码（服务端在此决定验证类型）。返回 type：
     * 1/2/3=Text 短信码（可自动）；其他见类注释类型表。-1 = 非零 code/解析失败（上层转 WebView 兜底）。
     */
    suspend fun obtainCaptcha(vat: String, salt: String): Int {
        val ac = anti()
        val (key, iv) = sessionKey ?: fail("会话密钥缺失，须先 vcPreCk")
        val params = JSONObject()
            .put("anti_content", ac)
            .put("verify_auth_token", vat)
            .put("captcha_collect", "") // 风控采集 token（逆向 Wt SDK 未做，实测空值是否放行）
            .put("salt", salt)
            .put("wp", false)
        val resp = post("api/phantom/obtain_captcha", encryptBody(params, vat, key, iv), ac)
        val outer = parseJson(resp) ?: return -1
        val data = decryptResponse(outer, key, iv) ?: return -1
        if (data.optInt("code", -1) != 0) {
            AppLog.w(TAG, "obtainCaptcha 非零 code=${data}")
            return -1
        }
        AppLog.d(TAG, "obtainCaptcha type=${data.optInt("type", -1)} raw=${data.toString().take(200)}")
        return data.optInt("type", -1)
    }

    /** 提交短信验证码（Text 类型）。返回 true = 服务端受理通过 */
    suspend fun userVerify(vat: String, salt: String, code: String): Boolean {
        val ac = anti()
        val (key, iv) = sessionKey ?: fail("会话密钥缺失，须先 vcPreCk")
        val params = JSONObject()
            .put("anti_content", ac)
            .put("verify_auth_token", vat)
            .put("salt", salt)
            .put("captcha_collect", "")
            .put("verify_code", code)
        val resp = post("api/phantom/user_verify", encryptBody(params, vat, key, iv), ac)
        val outer = parseJson(resp) ?: return false
        val data = decryptResponse(outer, key, iv) ?: return false
        AppLog.d(TAG, "userVerify resp=${data.toString().take(200)}")
        return data.optInt("code", -1) == 0
    }

    // ---------- type=101 知识验证（2026-10-21 CDP 抓包实测协议） ----------

    /**
     * 拉取验证题（challenge_captcha）。请求同 obtainCaptcha 的加密信封；
     * 响应为明文 JSON：{code, question_id, question_desc, question_style, correct_answer_cnt, options:{key:text}}。
     * 答错后再次调用即取下一题。
     */
    suspend fun challengeCaptcha(vat: String, salt: String): QuizQuestion? {
        val ac = anti()
        val (key, iv) = sessionKey ?: fail("会话密钥缺失，须先 vcPreCk")
        val params = JSONObject()
            .put("anti_content", ac)
            .put("verify_auth_token", vat)
            .put("salt", salt)
        val resp = post("api/phantom/challenge_captcha", encryptBody(params, vat, key, iv), ac)
        val data = parseJson(resp)?.let { decryptResponse(it, key, iv) } ?: parseJson(resp)
        if (data == null || data.optInt("code", -1) != 0) {
            AppLog.w(TAG, "challengeCaptcha 失败 raw=${resp.body.take(200)}")
            return null
        }
        val opts = data.optJSONObject("options") ?: return null
        val map = buildMap {
            for (k in opts.keys()) put(k, opts.optString(k))
        }
        val q = QuizQuestion(
            questionId = data.optInt("question_id", -1),
            desc = data.optString("question_desc"),
            style = data.optString("question_style"),
            options = map,
            correctAnswerCnt = data.optInt("correct_answer_cnt", 1).coerceAtLeast(1),
            questionOrder = data.optInt("question_order", 0),
        )
        AppLog.d(TAG, "challengeCaptcha 题目 id=${q.questionId} style=${q.style} opts=${map.values}")
        return q
    }

    /**
     * 提交答案（challenge_verify，明文 JSON）。
     * 返回 code：0=全部通过（接着 authTicket 收尾）；3002=答错（取下一题）；其他=异常。
     * questionOrderNumber 从 1 起，每次提交 +1。
     */
    suspend fun challengeVerify(
        vat: String,
        salt: String,
        questionId: Int,
        optionKeys: List<String>,
        questionOrderNumber: Int,
    ): Int {
        val ac = anti()
        val body = JSONObject()
            .put("anti_content", ac)
            .put("verify_auth_token", vat)
            .put("useNativeAntiToken", true)
            .put("salt", salt)
            .put("question_id", questionId)
            .put("user_options", org.json.JSONArray(optionKeys))
            .put("question_order_number", questionOrderNumber)
        val resp = post("api/phantom/challenge_verify", body, ac)
        val data = parseJson(resp)
        val code = data?.optInt("code", -1) ?: -1
        AppLog.d(TAG, "challengeVerify qid=$questionId opts=$optionKeys order=$questionOrderNumber code=$code")
        return code
    }

    // ---------- HTTP ----------

    private class Resp(val setCookies: List<String>, val body: String)

    private suspend fun post(api: String, body: JSONObject, ac: String): Resp {
        val request = Request.Builder()
            .url("$PROXY_BASE/$api?pdduid=0")
            .header("anti-content", ac)
            .header("Referer", "https://mobile.yangkeduo.com/login.html")
            .post(body.toString().toRequestBody("application/json;charset=UTF-8".toMediaType()))
            .build()
        return try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { resp ->
                    Resp(resp.headers("Set-Cookie"), resp.body?.string().orEmpty())
                }
            }
        } catch (e: IOException) {
            throw PlatformError.NetworkError(Platform.PDD, e)
        }
    }

    private suspend fun anti(): String = try {
        antiContent.generate()
    } catch (e: Exception) {
        throw PlatformError.ParseFailed(Platform.PDD, "anti-content 生成失败：${e.message}")
    }

    private fun parseJson(resp: Resp): JSONObject? = try {
        JSONObject(resp.body)
    } catch (_: Exception) {
        null
    }

    private fun parseCookieMap(setCookies: List<String>): Map<String, String> =
        setCookies.mapNotNull { raw ->
            val first = raw.substringBefore(';').trim()
            val idx = first.indexOf('=')
            if (idx <= 0) null else first.substring(0, idx).trim() to first.substring(idx + 1).trim()
        }.toMap()

    private fun fail(msg: String): Nothing = throw PlatformError.ParseFailed(Platform.PDD, msg)

    // ---------- 加密（页面对应：模块 45328 EY/Tn/Zs、14419 encrypt/encryptParams、RSA 公钥硬编码）----------

    /** AES-CBC-PKCS5 + Base64（crypto-js WordArray key 模式） */
    private fun aesEncrypt(plain: String, key: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun aesDecrypt(b64: String, key: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(Base64.decode(b64, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    /** {e:true, verify_auth_token(明文), content:AES(其余参数)}（页面 encryptParams） */
    private fun encryptBody(params: JSONObject, vat: String, key: ByteArray, iv: ByteArray): JSONObject =
        JSONObject()
            .put("e", true)
            .put("verify_auth_token", vat)
            .put("content", aesEncrypt(params.toString(), key, iv))

    /** 响应 {e:true, content} → 解密 JSONObject（页面 decryptResponse） */
    private fun decryptResponse(outer: JSONObject, key: ByteArray, iv: ByteArray): JSONObject? {
        if (!outer.optBoolean("e", false)) return outer
        val content = outer.optString("content")
        if (content.isBlank()) return null
        return try {
            JSONObject(aesDecrypt(content, key, iv))
        } catch (_: Exception) {
            null
        }
    }

    /** RSA-2048 PKCS#1 加密 → Base64（页面 K.prototype.encrypt = EME-PKCS1-v1.5） */
    private fun rsaEncryptB64(plain: String): String {
        val pub = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(BigInteger(1, hexToBytes(RSA_N_HEX)), BigInteger.valueOf(65537)),
        )
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, pub)
        return Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /** 随机 16 字符 key/iv（页面 45328：a(16) 从 dR 字符集取样） */
    private fun randomKeyIv(): Pair<ByteArray, ByteArray> {
        val rnd = SecureRandom()
        fun rand16(): ByteArray = ByteArray(16) { CHARSET[rnd.nextInt(CHARSET.length)].code.toByte() }
        return rand16() to rand16()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.removePrefix("00") // 页面 setPublic 的 modulus 带 00 前导
        val len = clean.length / 2
        return ByteArray(len) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private companion object {
        const val TAG = "PddQr"
        const val SCENE = "AUTH_LOGIN"

        /** 登录页 bundle 硬编码 ct=512（qrCodeAppId） */
        const val QR_APP_ID = 512

        const val PROXY_BASE = "https://mobile.yangkeduo.com/proxy/api"

        /** vc_pre_ck RSA 密钥交换公钥 modulus（bundle Z() 硬编码，e=010001） */
        const val RSA_N_HEX =
            "00B5F32E7F62AC544E16C185366CC4D743A28C6D23D94648862530A1A984090ED6B59C6C8F04C487424" +
                "5F26C9693C8085AE5E36A411D29D769C2210FFEDFE330254812D88C559E9618AE945FD4AD197FE8E4C148B439F3C277E78B38B90058759C8EAC3E67C7A6EB4B3B76650971BCA6B6E41FC74437A4012ECE410D8B0AD194C1B5CDD8BED891670274A4F88AAA4438361A384A5CA89EA6DBAB27E7934A5B82EFD5854D114803195572EAC50908C9335A523B81AD0C5AFA8E50C626B403965F2622CF38BDD2246A807B552D7344D6EE1A6E30A4E3B3A4A94C6CA76C3A96782639521FF91D3C0C076150F117DEE1E36E0A35E8A50AC1A465686DAD1AB7078592C1"

        /** 页面 63132 dR 字符集（AES key/iv 取样空间） */
        const val CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    }
}
