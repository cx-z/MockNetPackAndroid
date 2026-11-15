package com.mocknetpack.android

import org.junit.Assert.assertEquals
import org.junit.Test

/// 跨端门限契约：服务端 `pkg/capture.ImageInlineLimit` 与本端抓包上限必须同为 1 MiB。
/// 服务端靠“服务端见到 >1 MiB 的 image/* 只能是截断样本”来分流大图，门限一旦比抓包上限
/// 大，截断的图片就会被当成完整图片内联展示。
///
/// 三个仓库各自独立检出、独立 CI，无法在一条测试里互读源码，因此每一端都把自己的常量
/// 钉死在这个字面值上（契约文本见服务端 `openapi/mocknetpack.yaml`）。谁漂移，谁自己的
/// CI 先红；改这个数字时必须同时改服务端与 iOS。
///
/// 能抓的只是单边漂移：若三处字面值被同一次改动一起改掉，本测试不会拦。
class BodyLimitContractTest {

    @Test
    fun bodyLimitMatchesContract() {
        assertEquals(
            "BODY_LIMIT_BYTES 必须与服务端 ImageInlineLimit 和 iOS bodyLimit 同为 1 MiB",
            1_048_576L,
            MockNetPack.BODY_LIMIT_BYTES,
        )
    }
}
