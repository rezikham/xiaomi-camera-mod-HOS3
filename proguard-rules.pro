-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list

# libm9base binds its JNI entry points by fully-qualified class and method name
# (Java_com_codex_leicaapp_LegendaryPostProcessor_applyOriginalFilter / _applyMonochrome).
-keep class com.codex.leicaapp.LegendaryPostProcessor { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
