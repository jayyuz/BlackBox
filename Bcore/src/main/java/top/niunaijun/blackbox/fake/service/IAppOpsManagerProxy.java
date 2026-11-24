package top.niunaijun.blackbox.fake.service;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.app.BRAppOpsManager;
import black.android.os.BRServiceManager;
import black.com.android.internal.app.BRIAppOpsServiceStub;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.MethodParameterUtils;

/**
 * Created by Milk on 4/2/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class IAppOpsManagerProxy extends BinderInvocationStub {
    public IAppOpsManagerProxy() {
        super(BRServiceManager.get().getService(Context.APP_OPS_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder call = BRServiceManager.get().getService(Context.APP_OPS_SERVICE);
        return BRIAppOpsServiceStub.get().asInterface(call);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        if (BRAppOpsManager.get(null)._check_mService() != null) {
            AppOpsManager appOpsManager = (AppOpsManager) BlackBoxCore.getContext().getSystemService(Context.APP_OPS_SERVICE);
            try {
                BRAppOpsManager.get(appOpsManager)._set_mService(getProxyInvocation());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        replaceSystemService(Context.APP_OPS_SERVICE);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        MethodParameterUtils.replaceFirstAppPkg(args);
        MethodParameterUtils.replaceLastUid(args);
        // A guest runs under the HOST's uid, so any uid it passes to AppOps must be
        // the host's. replaceLastUid only rewrites an exact buid match, while
        // AppOps callers pass Process.myUid() (the virtual uid) or a plain appId.
        // Leaving a virtual uid paired with the rewritten host package makes
        // AppOpsService reject the call outright:
        //   SecurityException: Specified package "top.niunaijun.blackboxa64"
        //   under uid 10008 but it is not
        forceHostUid(args);
        return super.invoke(proxy, method, args);
    }

    /**
     * Rewrites every uid argument to the host uid.
     *
     * <p>AppOps is a uid/package-scoped service: system_server verifies that the
     * package passed alongside a uid really belongs to it. A guest has no uid of
     * its own, and the binder call genuinely originates from the host process, so
     * the host uid is the only value that can pass that check.
     */
    private static void forceHostUid(Object[] args) {
        if (args == null) {
            return;
        }
        int hostUid = BlackBoxCore.getHostUid();
        for (int i = 0; i < args.length; i++) {
            if (!(args[i] instanceof Integer)) {
                continue;
            }
            int value = (Integer) args[i];
            // Only touch values that look like a uid: the virtual uid, a bare appId,
            // or the host uid itself (no-op). Small ints are op codes / modes / flags
            // and must be left alone.
            if (value == hostUid) {
                continue;
            }
            if (value == BActivityThread.getBUid() || value == BActivityThread.getBAppId()) {
                args[i] = hostUid;
            }
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("noteProxyOperation")
    public static class NoteProxyOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @ProxyMethod("checkPackage")
    public static class CheckPackage extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // todo
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @ProxyMethod("checkOperation")
    public static class CheckOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (isManageExternalStorage(args)) {
                return AppOpsManager.MODE_ALLOWED;
            }
            MethodParameterUtils.replaceFirstAppPkg(args);
            MethodParameterUtils.replaceLastUid(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("checkOperationRaw")
    public static class CheckOperationRaw extends CheckOperation {
    }

    @ProxyMethod("checkOperationRawForDevice")
    public static class CheckOperationRawForDevice extends CheckOperation {
    }

    @ProxyMethod("noteOperation")
    public static class NoteOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (isManageExternalStorage(args)) {
                return AppOpsManager.MODE_ALLOWED;
            }
            MethodParameterUtils.replaceFirstAppPkg(args);
            MethodParameterUtils.replaceLastUid(args);
            return method.invoke(who, args);
        }
    }

    /**
     * Grants "all files access" to every guest whose operation is asked about.
     *
     * <p>{@code Environment.isExternalStorageManager()} resolves to
     * {@code AppOpsManager.noteOpNoThrow(OPSTR_MANAGE_EXTERNAL_STORAGE,
     * Process.myUid(), opPackageName, ...)}. Inside a guest {@code Process.myUid()}
     * reports the VIRTUAL uid, which the real AppOps service has never seen, so the
     * lookup fails and the call answers MODE_ERRORED. Apps that gate their file
     * browsing on that answer (MIUI Gallery, file managers, some camera apps) show
     * "all files access not granted" even though the HOST does hold the permission.
     *
     * <p>The host is the process that actually touches the filesystem, and BlackBox
     * redirects every guest path into its own sandbox, so answering ALLOWED here
     * matches what the host can really do.
     */
    @ProxyMethod("noteOpNoThrow")
    public static class NoteOpNoThrow extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (isManageExternalStorage(args)) {
                return AppOpsManager.MODE_ALLOWED;
            }
            MethodParameterUtils.replaceFirstAppPkg(args);
            MethodParameterUtils.replaceLastUid(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("noteOp")
    public static class NoteOp extends NoteOpNoThrow {
    }

    @ProxyMethod("unsafeCheckOpNoThrow")
    public static class UnsafeCheckOpNoThrow extends NoteOpNoThrow {
    }

    @ProxyMethod("unsafeCheckOp")
    public static class UnsafeCheckOp extends NoteOpNoThrow {
    }

    @ProxyMethod("checkOpNoThrow")
    public static class CheckOpNoThrow extends NoteOpNoThrow {
    }

    @ProxyMethod("checkOp")
    public static class CheckOp extends NoteOpNoThrow {
    }

    @ProxyMethod("startOp")
    public static class StartOp extends NoteOpNoThrow {
    }

    /**
     * AppOps operation for "all files access".
     *
     * <p>{@code AppOpsManager.OPSTR_MANAGE_EXTERNAL_STORAGE} is {@code @hide}; the
     * value is copied from AOSP. Only the string form is matched on purpose: the
     * int constant's value is generated from {@code AppOpEnums} and shifts between
     * releases, while the string is part of the stable AppOps wire format.
     * {@code Environment.isExternalStorageManager()} uses the string overload.
     */
    private static final String OPSTR_MANAGE_EXTERNAL_STORAGE =
            "android:manage_external_storage";

    /**
     * True when the AppOps call is about {@code MANAGE_EXTERNAL_STORAGE}.
     */
    private static boolean isManageExternalStorage(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof String && OPSTR_MANAGE_EXTERNAL_STORAGE.equals(arg)) {
                return true;
            }
        }
        return false;
    }
}
