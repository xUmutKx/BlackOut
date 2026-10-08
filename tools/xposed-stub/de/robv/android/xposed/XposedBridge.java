package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.Set;

public class XposedBridge {
    public static void log(String text) {}
    public static void log(Throwable t) {}
    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) { return null; }
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) { return null; }
    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) { return null; }
}
