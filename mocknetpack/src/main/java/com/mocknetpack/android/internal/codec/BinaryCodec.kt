package com.mocknetpack.android.internal.codec

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

/**
 * 二进制协议 gzip 压缩（对齐 iOS BinaryCodec）。
 *
 * 与 iOS 一致输出标准 gzip（1f 8b 头 + deflate + CRC32 + ISIZE）：
 * java.util.zip.GZIPOutputStream 即为标准 gzip 封装。
 * 注意：解压不在 SDK 内——已定由业务方 responseDecrypt 闭包完成
 * （对应 iOS「decodeAes(data, ungzip: true)」语义）。
 */
object BinaryCodec {

    /// 把字节按标准 gzip 格式压缩；空数据或压缩失败返回 null。
    fun gzipCompressed(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        return try {
            ByteArrayOutputStream(data.size / 2 + 64).use { bos ->
                GZIPOutputStream(bos).use { it.write(data) }
                bos.toByteArray()
            }
        } catch (e: IOException) {
            null
        }
    }
}
