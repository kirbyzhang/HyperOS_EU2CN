package local.pudding.weathercompat;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Report activation only in the compatibility manager's own process. */
public final class SelfModule implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam app) {
        if (!CompatConfig.MODULE_PACKAGE.equals(app.packageName)) return;
        XposedHelpers.findAndHookMethod(MainActivity.class.getName(), app.classLoader,
                "isModuleActive", XC_MethodReplacement.returnConstant(true));
    }
}
