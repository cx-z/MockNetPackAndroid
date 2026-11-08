package com.mocknetpack.android.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.Test;

/**
 * 插件行为测试（Gradle ProjectBuilder）：
 * - fail-open：非 Android 工程（没有 AGP / Android Components 扩展）应用插件时
 *   只 WARN 跳过，不抛异常；
 * - kill switch：{@code -Pmocknetpack.instrument=false}（等价于项目属性 false）
 *   时直接跳过插桩。
 *
 * 说明：Gradle TestKit 的注入 classpath 与项目 buildscript classpath 相互隔离，
 * 无法同时提供 AGP API 类，故这里用 ProjectBuilder 直接对真实 Project 应用插件；
 * 真实变体织入的字节码校验在宿主 Dokimo debug 产物上完成（见执行日志）。
 */
public class MockNetPackPluginBehaviorTest {

    private Project project() {
        return ProjectBuilder.builder().withName("fixture-" + System.nanoTime()).build();
    }

    @Test
    public void applyingToNonAndroidProjectFailsOpen() {
        Project project = project();
        try {
            project.getPluginManager().apply(MockNetPackInstrumentPlugin.class);
        } catch (Throwable t) {
            fail("非 Android 工程必须 fail-open，不应抛出：" + t);
        }
        assertTrue(
                "应创建 mocknetpack 扩展",
                project.getExtensions().findByName("mocknetpack") instanceof MockNetPackExtension);
    }

    @Test
    public void killSwitchPropertyDisablesInstrumentation() {
        Project project = project();
        project.getExtensions().getExtraProperties().set(MockNetPackInstrumentPlugin.PROPERTY, "false");
        assertFalse(
                "属性为 false 时必须视为关闭",
                MockNetPackInstrumentPlugin.isPropertyEnabled(project));
    }

    @Test
    public void defaultIsEnabled() {
        assertTrue(MockNetPackInstrumentPlugin.isPropertyEnabled(project()));
    }

    @Test
    public void extensionDefaultsToPropertyValue() {
        Project project = project();
        project.getPluginManager().apply(MockNetPackInstrumentPlugin.class);
        MockNetPackExtension extension =
                (MockNetPackExtension) project.getExtensions().getByName("mocknetpack");
        assertEquals(Boolean.TRUE, extension.getEnabled().getOrNull());
    }
}
