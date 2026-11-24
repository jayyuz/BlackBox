package top.niunaijun.blackbox.core.system.pm;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.os.Parcel;
import android.os.Process;
import android.util.ArrayMap;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.user.BUserHandle;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.PackageParserCompat;

/**
 * Created by Milk on 4/13/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
/*public*/ class Settings {
    public static final String TAG = "Settings";

    final ArrayMap<String, BPackageSettings> mPackages = new ArrayMap<>();
    private final Map<String, Integer> mAppIds = new HashMap<>();
    private final Map<String, SharedUserSetting> mSharedUsers = SharedUserSetting.sSharedUsers;
    private int mCurrUid = 0;

    public Settings() {
        synchronized (mPackages) {
            loadUidLP();
            SharedUserSetting.loadSharedUsers();
        }
    }

    BPackageSettings getPackageLPw(String name, PackageParser.Package aPackage, InstallOption installOption) {
        BPackageSettings pkgSettings;
        BPackageSettings origSettings = new BPackageSettings();
        origSettings.pkg = new BPackage(aPackage);
        origSettings.pkg.installOption = installOption;
        origSettings.installOption = installOption;
        origSettings.pkg.mExtras = origSettings;
        // Only replace the parsed ApplicationInfo when the regeneration actually
        // succeeded: assigning null here is what left records without one, and
        // every PackageInfo built from them was unusable.
        ApplicationInfo generated = PackageManagerCompat.generateApplicationInfo(
                origSettings.pkg, 0, BPackageUserState.create(), 0);
        if (generated != null) {
            origSettings.pkg.applicationInfo = generated;
        }
        synchronized (mPackages) {
            pkgSettings = mPackages.get(name);
            if (pkgSettings != null) {
                origSettings.appId = pkgSettings.appId;
                origSettings.userState = pkgSettings.userState;
            } else {
                boolean b = registerAppIdLPw(origSettings);
                if (!b) {
                    throw new RuntimeException("registerAppIdLPw err.");
                }
            }
        }
        return origSettings;
    }

    boolean registerAppIdLPw(BPackageSettings p) {
        boolean createdNew = false;
        String sharedUserId = p.pkg.mSharedUserId;
        SharedUserSetting sharedUserSetting = null;
        if (sharedUserId != null) {
            sharedUserSetting = mSharedUsers.get(sharedUserId);
            if (sharedUserSetting == null) {
                sharedUserSetting = new SharedUserSetting(sharedUserId);
                sharedUserSetting.userId = acquireAndRegisterNewAppIdLPw(p);
                mSharedUsers.put(sharedUserId, sharedUserSetting);
            }
        }
        if (sharedUserSetting != null) {
            p.appId = sharedUserSetting.userId;
            Slog.d(TAG, p.pkg.packageName + " sharedUserId = " + sharedUserId + ", setAppId = " + p.appId);
        }
        if (p.appId == 0) {
            // Assign new user ID
            p.appId = acquireAndRegisterNewAppIdLPw(p);
        }
        if (p.appId < 0) {
            createdNew = false;
//            PackageManagerService.reportSettingsProblem(Log.WARN,
//                    "Package " + p.name + " could not be assigned a valid UID");
//            throw new PackageManagerException(INSTALL_FAILED_INSUFFICIENT_STORAGE,
//                    "Package " + p.name + " could not be assigned a valid UID");
        } else {
            createdNew = true;
        }
        saveUidLP();
        SharedUserSetting.saveSharedUsers();
        return createdNew;
    }

    private int acquireAndRegisterNewAppIdLPw(BPackageSettings obj) {
        // Let's be stupidly inefficient for now...
        Integer integer = mAppIds.get(obj.pkg.packageName);
        if (integer != null)
            return integer;

        if (mCurrUid >= Process.LAST_APPLICATION_UID) {
            return -1;
        }
        mCurrUid++;
        mAppIds.put(obj.pkg.packageName, mCurrUid);
        return Process.FIRST_APPLICATION_UID + mCurrUid;
    }

    private void saveUidLP() {
        Parcel parcel = Parcel.obtain();
        FileOutputStream fileOutputStream = null;
        AtomicFile atomicFile = new AtomicFile(BEnvironment.getUidConf());
        try {
            Set<String> pkgName = mPackages.keySet();
            for (String s : new HashSet<>(mAppIds.keySet())) {
                if (!pkgName.contains(s)) {
                    mAppIds.remove(s);
                }
            }
            parcel.writeInt(mCurrUid);
            parcel.writeMap(mAppIds);

            fileOutputStream = atomicFile.startWrite();
            FileUtils.writeParcelToOutput(parcel, fileOutputStream);
            atomicFile.finishWrite(fileOutputStream);
        } catch (Exception e) {
            e.printStackTrace();
            atomicFile.failWrite(fileOutputStream);
        } finally {
            parcel.recycle();
        }
    }

    private void loadUidLP() {
        Parcel parcel = Parcel.obtain();
        try {
            byte[] uidBytes = FileUtils.toByteArray(BEnvironment.getUidConf());
            parcel.unmarshall(uidBytes, 0, uidBytes.length);
            parcel.setDataPosition(0);

            mCurrUid = parcel.readInt();
            HashMap hashMap = parcel.readHashMap(HashMap.class.getClassLoader());
            synchronized (mAppIds) {
                mAppIds.clear();
                mAppIds.putAll(hashMap);
            }
        } catch (Exception e) {
//            e.printStackTrace();
        } finally {
            parcel.recycle();
        }
    }

    public void scanPackage() {
        synchronized (mPackages) {
            File appRootDir = BEnvironment.getAppRootDir();
            FileUtils.mkdirs(appRootDir);
            File[] apps = appRootDir.listFiles();
            for (File app : apps) {
                if (!app.isDirectory()) {
                    continue;
                }
                scanPackage(app.getName());
            }
        }
    }

    public void scanPackage(String packageName) {
        synchronized (mPackages) {
            updatePackageLP(BEnvironment.getAppDir(packageName));
        }
    }

    private void updatePackageLP(File app) {
        String packageName = app.getName();
        Parcel packageSettingsIn = Parcel.obtain();
        try {
            byte[] bPackageSettingsBytes = FileUtils.toByteArray(BEnvironment.getPackageConf(packageName));

            packageSettingsIn.unmarshall(bPackageSettingsBytes, 0, bPackageSettingsBytes.length);
            packageSettingsIn.setDataPosition(0);

            BPackageSettings bPackageSettings = new BPackageSettings(packageSettingsIn);
            adoptSettingsLP(packageName, bPackageSettings);
            Slog.d(TAG, "loaded Package: " + packageName);
        } catch (Throwable e) {
            // Unreadable settings (format change, truncated write, ...). The APK is
            // still on disk, so rebuild the record from it instead of deleting the
            // package: the old code wiped the app dir here, which silently
            // uninstalled every guest whose conf came from another build and left
            // later process starts with a package that "does not exist".
            Slog.e(TAG, "settings unreadable for " + packageName + ", rebuilding from apk", e);
            BPackageSettings recovered = null;
            try {
                recovered = rebuildSettingsLP(packageName);
                if (recovered != null) {
                    adoptSettingsLP(packageName, recovered);
                    Slog.d(TAG, "recovered Package: " + packageName);
                    return;
                }
            } catch (Throwable rebuildError) {
                Slog.e(TAG, "rebuild failed for " + packageName, rebuildError);
            }
            // Rebuild also failed: only now is the package genuinely gone.
            Slog.e(TAG, "bad Package: " + packageName);
            FileUtils.deleteDir(app);
            removePackage(packageName);
            BProcessManagerService.get().killAllByPackageName(packageName);
            BPackageManagerService.get().onPackageUninstalled(packageName, true, BUserHandle.USER_ALL);
        } finally {
            packageSettingsIn.recycle();
        }
    }

    /**
     * Registers a parsed settings record, repairing fields that a legacy or
     * partially-written conf may be missing.
     */
    private void adoptSettingsLP(String packageName, BPackageSettings bPackageSettings) throws Exception {
        if (bPackageSettings == null || bPackageSettings.pkg == null) {
            throw new IllegalStateException("settings has no BPackage: " + packageName);
        }
        if (bPackageSettings.installOption == null) {
            throw new IllegalStateException("settings has no InstallOption: " + packageName);
        }
        bPackageSettings.pkg.mExtras = bPackageSettings;
        if (bPackageSettings.installOption.isFlag(InstallOption.FLAG_SYSTEM)) {
            PackageInfo packageInfo = BlackBoxCore.getPackageManager().getPackageInfo(packageName, PackageManager.GET_META_DATA);
            String currPackageSourcePath = packageInfo.applicationInfo.sourceDir;
            if (!currPackageSourcePath.equals(bPackageSettings.pkg.baseCodePath)) {
                // update baseCodePath And Re install
                BProcessManagerService.get().killAllByPackageName(bPackageSettings.pkg.packageName);
                BPackageSettings newPkg = reInstallBySystem(packageInfo, bPackageSettings.installOption);
                bPackageSettings.pkg = newPkg.pkg;
            }
        } else {
            // Never persist null here: doing so made every later getPackageInfo()
            // fail in checkUseInstalledOrHidden and surface as a bind NPE.
            ApplicationInfo refreshed =
                    PackageManagerCompat.generateApplicationInfo(bPackageSettings.pkg, 0, BPackageUserState.create(), 0);
            if (refreshed != null) {
                bPackageSettings.pkg.applicationInfo = refreshed;
            } else if (bPackageSettings.pkg.applicationInfo == null) {
                throw new IllegalStateException("no ApplicationInfo for " + packageName);
            }
        }
        bPackageSettings.save();
        mPackages.put(bPackageSettings.pkg.packageName, bPackageSettings);
    }

    /**
     * Rebuilds a settings record from the installed APK, restoring per-user install
     * state from the directories that survived on disk.
     */
    private BPackageSettings rebuildSettingsLP(String packageName) {
        String apkPath = null;
        InstallOption option = null;
        File stored = BEnvironment.getBaseApkDir(packageName);
        if (stored.exists()) {
            apkPath = stored.getAbsolutePath();
            option = InstallOption.installByStorage();
        } else {
            try {
                ApplicationInfo systemInfo = BlackBoxCore.getPackageManager().getApplicationInfo(packageName, 0);
                apkPath = systemInfo.sourceDir;
                option = InstallOption.installBySystem();
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        if (apkPath == null) {
            return null;
        }
        PackageParser.Package aPackage = parserApk(apkPath);
        if (aPackage == null) {
            return null;
        }
        if (option.isFlag(InstallOption.FLAG_SYSTEM)) {
            try {
                aPackage.applicationInfo = BlackBoxCore.getPackageManager().getPackageInfo(packageName, 0).applicationInfo;
            } catch (PackageManager.NameNotFoundException e) {
                return null;
            }
        }
        BPackageSettings settings = getPackageLPw(packageName, aPackage, option);
        // Users are still "installed" wherever their data directory exists.
        File[] userDirs = BEnvironment.getDataUserRootDir().listFiles();
        if (userDirs != null) {
            for (File userDir : userDirs) {
                File appData = new File(userDir, packageName);
                if (!appData.isDirectory()) {
                    continue;
                }
                try {
                    settings.setInstalled(true, Integer.parseInt(userDir.getName()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return settings;
    }

    private BPackageSettings reInstallBySystem(PackageInfo systemPackageInfo, InstallOption option) throws Exception {
        Slog.d(TAG, "reInstallBySystem: " + systemPackageInfo.packageName);
        PackageParser.Package aPackage = parserApk(systemPackageInfo.applicationInfo.sourceDir);
        if (aPackage == null) {
            throw new RuntimeException("parser apk error.");
        }
        aPackage.applicationInfo = BlackBoxCore.getPackageManager().getPackageInfo(aPackage.packageName, 0).applicationInfo;
        return getPackageLPw(aPackage.packageName, aPackage, option);
    }

    public void removePackage(String packageName) {
        mPackages.remove(packageName);
    }

    private PackageParser.Package parserApk(String file) {
        try {
            PackageParser parser = PackageParserCompat.createParser(new File(file));
            PackageParser.Package aPackage = PackageParserCompat.parsePackage(parser, new File(file), 0);
            PackageParserCompat.collectCertificates(parser, aPackage, 0);
            return aPackage;
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return null;
    }
}
