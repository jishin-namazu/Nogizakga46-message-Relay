package com.nogirelay.app.data.api

import com.nogirelay.app.BuildConfig

/**
 * API 配置
 *
 * 默认服务器地址和访问令牌都来自本机构建配置（未提交的 local.properties）：
 * - relay.baseUrl      -> BuildConfig.DEFAULT_RELAY_URL
 * - relay.access.token -> BuildConfig.RELAY_ACCESS_TOKEN
 *
 * 两者默认为空，因此普通构建既不会预填服务器地址，也不会内置访问令牌。
 * 需要为特定构建预置时，在本机 local.properties 中提供上述两项后构建。
 * 不要把真实令牌写入受版本控制的源码。
 */
object ApiConfig {
    // Optional local build-time default; empty means "prefill nothing".
    val BASE_URL: String
        get() = BuildConfig.DEFAULT_RELAY_URL

    // Optional local build-time value; never commit a real token to source.
    val ACCESS_TOKEN: String
        get() = BuildConfig.RELAY_ACCESS_TOKEN
}
