package de.robv.android.xposed;

import java.util.Map;
import java.util.Set;

/** Stub: only the read methods BlackOut uses (the real class implements SharedPreferences). */
public class XSharedPreferences {
    public XSharedPreferences(String packageName, String prefFileName) {}
    public boolean makeWorldReadable() { return false; }
    public void reload() {}
    public Map<String, ?> getAll() { return null; }
    public boolean contains(String key) { return false; }
    public float getFloat(String key, float defValue) { return defValue; }
    public boolean getBoolean(String key, boolean defValue) { return defValue; }
    public int getInt(String key, int defValue) { return defValue; }
    public String getString(String key, String defValue) { return defValue; }
    public Set<String> getStringSet(String key, Set<String> defValues) { return defValues; }
}
