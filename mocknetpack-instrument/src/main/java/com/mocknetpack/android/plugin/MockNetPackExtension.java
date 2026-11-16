package com.mocknetpack.android.plugin;

import org.gradle.api.provider.Property;

/**
 * 插件 DSL：
 *
 * <pre>
 * mocknetpack {
 *     enabled = false   // 与 -Pmocknetpack.instrument=false 等价；默认 true（debug 变体生效）
 * }
 * </pre>
 */
public abstract class MockNetPackExtension {

    /** 是否启用插桩（默认 true，仅对 debug 变体生效）。 */
    public abstract Property<Boolean> getEnabled();

    /** H5 装配入口，格式为完整类名#无参方法名。缺省时跳过 H5 插桩。 */
    public abstract Property<String> getH5WebViewSetup();
}
