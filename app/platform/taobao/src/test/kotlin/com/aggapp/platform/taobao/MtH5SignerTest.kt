package com.aggapp.platform.taobao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 文档 §7 要求：MtH5Signer.sign 必须有单测（给定输入断言 MD5 输出） */
class MtH5SignerTest {

    @Test
    fun `md5Hex 已知向量`() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", MtH5Signer.md5Hex("abc"))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", MtH5Signer.md5Hex(""))
    }

    @Test
    fun `sign 按 token t appKey data 顺序拼接后取 MD5`() {
        // 文档 §7：sign = MD5(token + "&" + t + "&" + appKey + "&" + data)
        val expected = MtH5Signer.md5Hex("abc123def&_1758960000000&12574478&{}")
        assertEquals(expected, MtH5Signer.sign("abc123def", "1758960000000", "12574478", "{}"))
    }

    @Test
    fun `sign 输出为 32 位小写 hex`() {
        val sign = MtH5Signer.sign("token", "1700000000000", "12574478", "{}")
        assertTrue(sign.matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun `extractToken 取 _m_h5_tk 下划线前段`() {
        assertEquals(
            "abc123def456abc123def456abc123de",
            MtH5Signer.extractToken("abc123def456abc123def456abc123de_1758960000000"),
        )
        assertEquals("", MtH5Signer.extractToken(null))
        assertEquals("", MtH5Signer.extractToken(""))
        assertEquals("", MtH5Signer.extractToken("_1758960000000"))
    }
}
