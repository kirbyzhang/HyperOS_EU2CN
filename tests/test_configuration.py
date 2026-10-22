# SPDX-License-Identifier: GPL-3.0-only
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ConfigurationTests(unittest.TestCase):
    def test_public_defaults_keep_local_repairs_without_experiments(self):
        public = (ROOT / "config/EU2CN_Config.ini").read_bytes()
        self.assertEqual(public, (ROOT / "build/pudding-eu-localization/EU2CN_Config.ini").read_bytes())
        values = dict(line.split("=", 1) for line in public.decode().splitlines() if "=" in line and not line.startswith("#"))
        self.assertEqual({k for k, v in values.items() if v == "true"},
                         {"HookXSpace", "HookXSpaceSafety", "HookGoogleHome", "HookWidgets", "HookSystemUiIcons"})
        for key in ("ToolsBox", "HookInternational", "HookFcm", "CnXmsf", "MiPush"):
            self.assertEqual(values[key], "false")

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_actual_international_policy_boundaries(self):
        cache = ROOT / ".cache/tmp"
        cache.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="policy-", dir=cache) as directory:
            work = Path(directory)
            harness = work / "PolicyCheck.java"
            harness.write_text('''import local.pudding.eulocalization.InternationalPolicy;
public final class PolicyCheck {
 public static void main(String[] ignored) {
  String[] targets={"com.miui.securitycore","com.miui.home","android","com.miui.powerkeeper","com.miui.core"};
  for(int mask=0;mask<16;mask++) {
   boolean legacy=(mask&1)!=0,safety=(mask&2)!=0,fcm=(mask&4)!=0,home=(mask&8)!=0;
   boolean[] expected={legacy||safety,legacy||home,legacy||fcm,legacy||fcm,legacy||fcm};
   for(int i=0;i<targets.length;i++)
    if(InternationalPolicy.applies(targets[i],legacy,safety,fcm,home)!=expected[i])throw new AssertionError(targets[i]+":"+mask);
   for(String outside:new String[]{"me.weishu.kernelsu","com.miui.weather2","com.android.phone","com.android.quicksearchbox","com.xiaomi.xmsf",null})
    if(InternationalPolicy.applies(outside,legacy,safety,fcm,home))throw new AssertionError("scope leak");
  }
 }
}''')
            source = ROOT / "toolsbox-hyperos3-pudding/app/src/main/java/local/pudding/eulocalization/InternationalPolicy.java"
            subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(work), str(source), str(harness)], check=True, capture_output=True)
            subprocess.run(["java", "-cp", str(work), "PolicyCheck"], check=True, capture_output=True)


if __name__ == "__main__":
    unittest.main()
