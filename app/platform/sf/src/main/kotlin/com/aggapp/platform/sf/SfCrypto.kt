package com.aggapp.platform.sf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 驿收发响应解密 / JWT 解析工具。
 *
 * ① 响应 AES 解密（sf.md §1.3，移植自解包 utils/aes_util.js `decryptECB` + utils/api/http.js）：
 * 部分接口响应为 `{"success":...,"code":...,"k":"...","v":"..."}` 加密形态，需解密后才是真正的响应信封 JSON：
 * ```
 * key = SHA1("iYsM5rKhgq2NNoMX" + k) 的前 16 字节   // aes_util.js: SHA1(...).words.slice(0,4)
 * v   = Base64(AES-ECB-PKCS7 密文)                  // crypto-js AES.decrypt(String) 按 Base64 解析
 * 明文 = UTF-8 JSON（完整响应信封）
 * ```
 *
 * ② JWT(HS256) payload 本地解码（sf.md §1.4：YJY-Auth 即 JWT，payload 含 userId / openId / mobile / exp / jti）。
 */
internal object SfCrypto {

    /** k/v 解密前缀（http.js 硬编码常量） */
    private const val K_PREFIX = "iYsM5rKhgq2NNoMX"

    /** 解密 k/v 响应，返回明文 JSON 字符串（即完整响应信封） */
    fun decryptKv(k: String, v: String): String {
        // 密钥：SHA1(prefix + k) 前 16 字节（aes_util.js 取 SHA1 words 前 4 个 = 16 字节）
        val digest = MessageDigest.getInstance("SHA-1").digest((K_PREFIX + k).toByteArray(Charsets.UTF_8))
        val key = digest.copyOf(16)
        // JVM 的 PKCS5Padding 对 16 字节分组即 CryptoJS 的 PKCS7
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        // crypto-js 对字符串密文默认按 OpenSSL 格式解析：无 Salted__ 头 → 整体为标准 Base64 密文
        val plain = cipher.doFinal(Base64.getDecoder().decode(v))
        return String(plain, Charsets.UTF_8)
    }

    /** 解码 JWT payload（base64url → JSON 对象）；token 非 JWT / 格式异常返回 null，不抛 */
    fun parseJwtPayload(token: String): JsonObject? = runCatching {
        val payload = token.split('.').getOrNull(1) ?: return@runCatching null
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)).jsonObject
    }.getOrNull()
}
