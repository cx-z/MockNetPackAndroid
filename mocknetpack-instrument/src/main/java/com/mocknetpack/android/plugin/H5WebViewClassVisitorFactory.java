package com.mocknetpack.android.plugin;

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import com.android.build.api.instrumentation.InstrumentationParameters;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/** 将 H5 插桩限制在配置中的宿主类。 */
public abstract class H5WebViewClassVisitorFactory
        implements AsmClassVisitorFactory<H5WebViewClassVisitorFactory.Parameters> {

    private static final String WEB_VIEW_CLASS = "android.webkit.WebView";

    public interface Parameters extends InstrumentationParameters {
        @Input Property<String> getSetupClass();
        @Input Property<String> getSetupMethod();
    }

    @Override
    public boolean isInstrumentable(ClassData classData) {
        return getParameters().get().getSetupClass().get().equals(classData.getClassName());
    }

    @Override
    public ClassVisitor createClassVisitor(ClassContext classContext, ClassVisitor next) {
        int api = getInstrumentationContext().getApiVersion().getOrElse(Opcodes.ASM9);
        Parameters parameters = getParameters().get();
        return new H5WebViewClassVisitor(api, next, parameters.getSetupClass().get(),
                parameters.getSetupMethod().get(), owner -> isWebView(classContext, owner));
    }

    /** 仅识别 Android WebView 及可解析的子类。 */
    private static boolean isWebView(ClassContext context, String owner) {
        String name = owner.replace('/', '.');
        if (WEB_VIEW_CLASS.equals(name)) return true;
        ClassData data = context.loadClassData(name);
        return data != null && data.getSuperClasses().contains(WEB_VIEW_CLASS);
    }
}
