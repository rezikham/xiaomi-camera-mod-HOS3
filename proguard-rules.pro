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

# Keep MountHide (JNI entry points Java_com_codex..._nativeInit etc. depend on
# the class/method names).
-keep class io.martin.hypercameramod.MountHide { *; }
-keepclassmembers class io.martin.hypercameramod.MountHide {
    native <methods>;
}
