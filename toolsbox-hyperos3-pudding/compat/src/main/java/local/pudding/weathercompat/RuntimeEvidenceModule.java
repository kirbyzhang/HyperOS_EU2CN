package local.pudding.weathercompat;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import local.pudding.eulocalization.HookEvidence;

public final class RuntimeEvidenceModule implements IXposedHookLoadPackage {
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam app) {
        HookEvidence.record("process:" + app.packageName + ":" + app.processName, "ENTRY");
    }
}
