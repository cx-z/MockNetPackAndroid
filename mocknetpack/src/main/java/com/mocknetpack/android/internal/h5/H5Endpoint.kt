package com.mocknetpack.android.internal.h5

import okhttp3.HttpUrl
import java.util.Locale

/** Matches the normalized browser Origin and preserves the encoded pathname as reported. */
internal data class H5Endpoint(val origin: String, val path: String, val query: String) {
    fun key(method: String): String = method.uppercase(Locale.ROOT) + " " + origin + path

    companion object {
        private val methodToken = Regex("^[!#\$%&'*+.^_`|~0-9A-Za-z-]{1,32}$")

        fun validMethod(method: String): Boolean = methodToken.matches(method)

        fun parse(url: String): H5Endpoint? {
            if (url.length > 4096 || url.toByteArray(Charsets.UTF_8).size > 4096) return null
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) return null
            val suffixAt = url.indexOfAny(charArrayOf('/', '?', '#'), schemeEnd + 3)
                .takeIf { it >= 0 } ?: url.length
            val authority = HttpUrl.parse(url.substring(0, suffixAt)) ?: return null
            val host = if (':' in authority.host()) "[${authority.host()}]" else authority.host()
            val origin = "${authority.scheme()}://$host" +
                if (authority.port() == HttpUrl.defaultPort(authority.scheme())) "" else ":${authority.port()}"
            val suffix = url.substring(suffixAt).substringBefore('#')
            val path = suffix.substringBefore('?').ifEmpty { "/" }
            return H5Endpoint(origin, path, suffix.substringAfter('?', ""))
        }
    }
}
