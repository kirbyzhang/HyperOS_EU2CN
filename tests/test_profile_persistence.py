# SPDX-License-Identifier: GPL-3.0-only
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
class PersistenceTests(unittest.TestCase):
    def test_delayed_init_save_and_unrelated_state_guards(self):
        cache = ROOT / ".cache/tmp"
        cache.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="persistence-", dir=cache) as directory:
            work = Path(directory)
            harness = work / "PersistenceCheck.java"
            harness.write_text('''import java.nio.*;
import java.util.*;
import local.pudding.ksu.*;
public final class PersistenceCheck {
 static byte[] row(int uid,boolean root,boolean useDefault,boolean unmount){
  byte[] r=new byte[784];ByteBuffer b=ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN);
  b.putInt(0,4);b.putInt(260,uid);r[264]=(byte)(root?1:0);r[272]=(byte)(useDefault?1:0);r[273]=(byte)(unmount?1:0);return r;
 }
 static byte[] file(byte[]... rows){
  ByteBuffer b=ByteBuffer.allocate(8+rows.length*784).order(ByteOrder.LITTLE_ENDIAN);
  b.putInt(2135642965);b.putInt(4);for(byte[] r:rows)b.put(r);return b.array();
 }
 static final byte[] global=row(9999,false,true,true),root=row(11000,true,false,false);
 static final byte[] initial=file(global,root),saved=file(global,root,row(12002,false,false,false));
 static final Map<Integer,byte[]> before=MountPolicy.decode(initial);
 static class Store implements ProfilePersistence.Access {
  long clock=0,readyAt=0;int wakes=0,reads=0;boolean never=false,brokenFirst=false,denied=false;byte[] result=saved;
  public byte[] read(){reads++;if(brokenFirst&&reads==1)return new byte[0];return wakes>0&&clock>=readyAt&&!never?result:initial;}
  public void wakeInit(){if(denied)throw new SecurityException("denied");wakes++;}
  public long now(){return clock;}
  public void pause(long millis){clock+=millis;}
 }
 static void reject(Store s)throws Exception{
  try{ProfilePersistence.await(before,12002,s,30000);throw new AssertionError("unsafe result accepted");}
  catch(IllegalStateException expected){}
 }
 public static void main(String[] ignored)throws Exception{
  // Without waking init this store will never save, however long it is polled.
  Store needsWake=new Store();assert Arrays.equals(ProfilePersistence.await(before,12002,needsWake,30000),saved);assert needsWake.wakes==1;
  Store delayed=new Store();delayed.readyAt=10000;assert Arrays.equals(ProfilePersistence.await(before,12002,delayed,30000),saved);assert delayed.clock>=10000&&delayed.wakes>=5;
  Store already=new Store(){public byte[] read(){return saved;}};ProfilePersistence.await(before,12002,already,30000);assert already.wakes==0;
  Store truncated=new Store();truncated.brokenFirst=true;ProfilePersistence.await(before,12002,truncated,30000);
  Store globalChanged=new Store();globalChanged.result=file(row(9999,false,true,false),root,row(12002,false,false,false));reject(globalChanged);
  Store rootGranted=new Store();rootGranted.result=file(global,root,row(12002,true,false,false));reject(rootGranted);
  Store extraUid=new Store();extraUid.result=file(global,root,row(12002,false,false,false),row(13000,false,false,false));reject(extraUid);
  Store never=new Store();never.never=true;reject(never);assert never.clock==30000;
  Store denied=new Store();denied.denied=true;
  try{ProfilePersistence.await(before,12002,denied,30000);throw new AssertionError();}catch(SecurityException expected){}
  System.out.println("PASS init-wake requirement, >old-timeout save, already saved, partial file, global/Root/extra UID guards, bounded timeout and denied wake");
 }
}''')
            sources = ROOT / "toolsbox-hyperos3-pudding/ksu-helper/src/local/pudding/ksu"
            subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(work),
                            str(sources / "MountPolicy.java"), str(sources / "ProfilePersistence.java"), str(harness)],
                           check=True, capture_output=True)
            result = subprocess.run(["java", "-ea", "-cp", str(work), "PersistenceCheck"], check=True, capture_output=True)
            self.assertIn(b"PASS init-wake", result.stdout)
