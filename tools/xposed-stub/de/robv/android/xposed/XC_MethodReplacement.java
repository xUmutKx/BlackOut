package de.robv.android.xposed;

public abstract class XC_MethodReplacement extends XC_MethodHook {
    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;
    public static XC_MethodReplacement returnConstant(final Object result) { return null; }
}
