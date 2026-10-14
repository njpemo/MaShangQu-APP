package com.aggapp.core.model

/**
 * 聚合码编码策略集合。
 *
 * 目的：把四平台身份码字符串聚合进【一个 CODE128 条形码】。
 * CODE128 支持全 ASCII，任何策略都可直接渲染；
 * 线下测试阶段用同一份内容切换不同策略扫码，对比哪种格式能被人工/扫码端还原。
 *
 * 全部策略实现 [AggFormat]，输入为「平台 → 身份码内容」有序映射。
 */
interface AggFormat {
    /** 策略名（UI 展示，方便线下测试记录） */
    val name: String

    /** 格式说明（UI 展示） */
    val desc: String

    /** 编码。codes 至少包含一个平台条目。 */
    fun encode(codes: Map<Platform, String>): String
}

object AggFormats {

    /** 返回全部候选策略（顺序即 UI 默认切换顺序） */
    fun all(): List<AggFormat> = listOf(V1Pipe, V2LenPrefix, V3Json, V4Tsv, V5Hex)

    /** 校验/解析辅助：V2LenPrefix 可无损解析回平台→内容映射（自测用） */
    fun decodeV2(raw: String): Map<Platform, String>? = try {
        require(raw.startsWith("AGG2"))
        val body = raw.substring(4)
        val out = linkedMapOf<Platform, String>()
        var i = 0
        while (i < body.length) {
            val p = Platform.entries.first { it.name.first() == body[i] }
            val len = body.substring(i + 1, i + 5).toInt()
            out[p] = body.substring(i + 5, i + 5 + len)
            i += 5 + len
        }
        out
    } catch (e: Exception) {
        null
    }

    /**
     * V1 —— 竖线分隔拼接（最直观）
     * 格式：AGG1|P:<PDD内容>|T:<淘宝内容>|J:<京东内容>|S:<顺丰内容>
     * 仅当内容不含 | 时可无损还原。
     */
    object V1Pipe : AggFormat {
        override val name = "V1 竖线拼接"
        override val desc = "AGG1|P:xxx|T:xxx|J:xxx|S:xxx"
        private val letter = mapOf(Platform.PDD to 'P', Platform.TAOBAO to 'T', Platform.JD to 'J', Platform.SF to 'S')
        override fun encode(codes: Map<Platform, String>): String =
            codes.entries.joinToString("|", prefix = "AGG1") { "${letter[it.key]}:${it.value}" }
    }

    /**
     * V2 —— 长度前缀（二进制安全、可无损解析）
     * 格式：AGG2 + 循环[平台字母 + 4位内容长度 + 内容]
     */
    object V2LenPrefix : AggFormat {
        override val name = "V2 长度前缀"
        override val desc = "AGG2 + [平台1字符+4位长度+内容]×N，可无损解析"
        override fun encode(codes: Map<Platform, String>): String = buildString {
            append("AGG2")
            for ((p, c) in codes) {
                append(p.name.first())
                append(c.length.toString().padStart(4, '0'))
                append(c)
            }
        }
    }

    /**
     * V3 —— 紧凑 JSON（通用工具可读）
     * 格式：{"v":1,"p":"PDD:xxx;TB:xxx;..."}
     */
    object V3Json : AggFormat {
        override val name = "V3 紧凑JSON"
        override val desc = """{"v":1,"p":"PDD:xxx;TB:xxx;..."}"""
        override fun encode(codes: Map<Platform, String>): String =
            "{\"v\":1,\"p\":\"" + codes.entries.joinToString(";") { "${it.key.name}:${it.value}" } + "\"}"
    }

    /**
     * V4 —— 制表符分隔（部分扫码枪/小程序按分隔符切列）
     * 格式：PDD<TAB>xxx<TAB>TAOBAO<TAB>xxx...
     */
    object V4Tsv : AggFormat {
        override val name = "V4 制表符分隔"
        override val desc = "平台名\\t内容 依次拼接，无包头"
        override fun encode(codes: Map<Platform, String>): String =
            codes.entries.joinToString("\t") { "${it.key.name}\t${it.value}" }
    }

    /**
     * V5 —— 纯内容顺连（测试扫码端对「裸字符串」的兼容性）
     * 格式：各平台内容直接顺序拼接，无任何包头/分隔
     */
    object V5Hex : AggFormat {
        override val name = "V5 纯内容顺连"
        override val desc = "无包头无分隔，四段内容直接顺连（兼容性极限测试）"
        override fun encode(codes: Map<Platform, String>): String =
            codes.values.joinToString("")
    }
}
