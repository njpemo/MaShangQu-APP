package com.aggapp.platform.jd

/**
 * Jcap 验证码 ENC 编解码（jd.md §2.2.6④，移植自 jd/_jcap_rsa.js，Node 实测服务端接受）。
 *
 * 名为 rsa 实为 XXTEA 变体 + base64url：
 * - 明文先 UTF-8 编码，strToLongs 小端打包，末尾追加一个 long 存原始字节长度；
 * - XXTEA 主干（delta 0x9E3779B9，轮数 6+52/n，z 值跨迭代携带）+ 混淆附加项（见 mx() 注释）；
 * - key = "E736B80A35290F193C2034A8021CC63B"（32 字符 → 8 个 long；
 *   源码里 "1542964129" 只是 delta 常量的混淆拆分：1111471640+1542964129=0x9E3779B9）；
 * - 输出 longs 小端转字节序列后 base64url（字母表 -_，无 padding）。
 */
internal object JcapCodec {

    private const val DELTA = -1640531527 // 0x9E3779B9
    private const val KEY = "E736B80A35290F193C2034A8021CC63B"

    /** ENC：getEncryptData 等价实现（空输入原样返回空串，与源码一致） */
    fun encrypt(payload: String): String {
        if (payload.isEmpty()) return ""
        val v = toLongs(payload.toByteArray(Charsets.UTF_8), withLength = true)
        val k = toLongs(KEY.toByteArray(Charsets.US_ASCII), withLength = false)
            .let { if (it.size < 4) it.copyOf(4) else it }
        xxteaEncrypt(v, k)
        return b64Url(longsToBytes(v))
    }

    /** captchaRandom：0-9a-z 随机串；原实现 Math.floor(rand*35) 永远取不到 'z'，忠实复刻 */
    fun random(n: Int): String {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
        val sb = StringBuilder(n)
        repeat(n) { sb.append(alphabet[(Math.random() * 35).toInt()]) }
        return sb.toString()
    }

    /** complement：前导零补位到指定宽度 */
    fun pad(value: String, width: Int): String {
        var s = value
        while (s.length < width) s = "0$s"
        return s
    }

    /** 小端打包为 IntArray；withLength 时末尾追加一个 long 存字节数（XXTEA js 版约定） */
    private fun toLongs(bytes: ByteArray, withLength: Boolean): IntArray {
        val dataLongs = (bytes.size + 3) / 4
        val v = IntArray(if (withLength) dataLongs + 1 else dataLongs)
        for (i in bytes.indices) {
            v[i ushr 2] = v[i ushr 2] or ((bytes[i].toInt() and 0xFF) shl ((i and 3) shl 3))
        }
        if (withLength) v[v.size - 1] = bytes.size
        return v
    }

    private fun longsToBytes(v: IntArray): ByteArray {
        val out = ByteArray(v.size * 4)
        for (i in v.indices) {
            val x = v[i]
            out[i * 4] = (x and 0xFF).toByte()
            out[i * 4 + 1] = ((x ushr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((x ushr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((x ushr 24) and 0xFF).toByte()
        }
        return out
    }

    private fun xxteaEncrypt(v: IntArray, k: IntArray) {
        val n = v.size
        if (n < 2) return
        var z = v[n - 1]
        var sum = 0
        val rounds = 6 + 52 / n
        repeat(rounds) {
            sum += DELTA // Int 溢出自然回绕，与 JS o() 取模一致
            val e = (sum ushr 2) and 3
            for (p in 0 until n - 1) {
                val y = v[p + 1]
                val nv = v[p] + mx(sum, y, z, p, e, k)
                v[p] = nv
                z = nv
            }
            val y = v[0]
            val nv = v[n - 1] + mx(sum, y, z, n - 1, e, k)
            v[n - 1] = nv
            z = nv
        }
    }

    /**
     * MX：标准 XXTEA 与一个混淆附加项的异或（_jcap_rsa.js 的 u() 恒走 i-25700 隐藏分支，
     * 常量折叠后 a=74292 → c=2,d=2,l=3,h=4；已与原版 JS 对拍全串一致，见 jd/js/test_xxtea_port.js）。
     */
    private fun mx(sum: Int, y: Int, z: Int, p: Int, e: Int, k: IntArray): Int {
        val base = (((z ushr 5) xor (y shl 2)) + ((y ushr 3) xor (z shl 4))) xor
            ((sum xor y) + (k[(p and 3) xor e] xor z))
        val extra = ((y ushr 2) xor (z shl 2)) +
            (((sum ushr 3) and 63) xor (((z + y) ushr 1) and 63))
        return base xor extra
    }

    /** base64url（-_ 字母表，无 padding，与源码 JS 实现一致） */
    private fun b64Url(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
