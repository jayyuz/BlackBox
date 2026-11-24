package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;

import black.android.app.BRILocaleManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Hooks {@code android.app.ILocaleManager} (the binder behind
 * {@code LocaleManager}, API 33+).
 *
 * <p>Since Android 13, {@code AppCompatDelegate} pushes the app's stored locale
 * into the framework on every start ({@code AppLocalesStorageHelper
 * .syncLocalesToFramework}). {@code LocaleManagerService.setApplicationLocales}
 * then runs {@code isPackageOwnedByCaller(packageName, callingUid)} and, when the
 * package does not belong to the caller, requires the signature-level
 * {@code CHANGE_CONFIGURATION} permission.
 *
 * <p>A guest passes its own package name while the binder call is made by the
 * HOST's uid, so ownership never matches and the permission check always fails:
 *
 * <pre>
 * SecurityException: setApplicationLocales: Neither user 10470 nor current
 *   process has android.permission.CHANGE_CONFIGURATION.
 * </pre>
 *
 * <p>The host cannot be granted that permission (it is signature|privileged), so
 * the package name is rewritten to the HOST's instead: the host owns itself, the
 * ownership check passes without any permission, and the locale is applied to the
 * process that actually hosts the guest. Reads are rewritten the same way so the
 * guest gets back what it stored.
 */
public class ILocaleManagerProxy extends BinderInvocationStub {
    public static final String TAG = "ILocaleManagerProxy";

    /**
     * Value of {@code Context.LOCALE_SERVICE}, which only exists from API 33.
     * Referencing the constant directly would fail class verification on older
     * releases, where this proxy is simply never installed.
     */
    private static final String LOCALE_SERVICE = "locale";

    public ILocaleManagerProxy() {
        super(BRServiceManager.get().getService(LOCALE_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRILocaleManagerStub.get().asInterface(
                BRServiceManager.get().getService(LOCALE_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(LOCALE_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    /**
     * Replaces the caller-supplied package name with the host's.
     *
     * <p>{@code packageName} is the first String parameter on every locale API;
     * the userId int follows it. Rewriting the name makes the host own the call,
     * so {@code isPackageOwnedByCaller} succeeds and the signature-level
     * {@code CHANGE_CONFIGURATION} check is never reached.
     */
    private static void rewritePackageName(Object[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof String) {
                args[i] = BlackBoxCore.getHostPkg();
                return;
            }
        }
    }

    @ProxyMethod("setApplicationLocales")
    public static class SetApplicationLocales extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            rewritePackageName(args);
            try {
                return method.invoke(who, args);
            } catch (Throwable e) {
                // Applying a per-app locale is cosmetic; never take the guest down
                // over it. AppCompat runs this on a worker thread whose uncaught
                // exception kills the process.
                Slog.w(TAG, "setApplicationLocales failed, ignoring", e);
                return null;
            }
        }
    }

    @ProxyMethod("getApplicationLocales")
    public static class GetApplicationLocales extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            rewritePackageName(args);
            try {
                return method.invoke(who, args);
            } catch (Throwable e) {
                Slog.w(TAG, "getApplicationLocales failed", e);
                return null;
            }
        }
    }

    @ProxyMethod("setOverrideLocaleConfig")
    public static class SetOverrideLocaleConfig extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            rewritePackageName(args);
            try {
                return method.invoke(who, args);
            } catch (Throwable e) {
                Slog.w(TAG, "setOverrideLocaleConfig failed, ignoring", e);
                return null;
            }
        }
    }

    @ProxyMethod("getOverrideLocaleConfig")
    public static class GetOverrideLocaleConfig extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            rewritePackageName(args);
            try {
                return method.invoke(who, args);
            } catch (Throwable e) {
                Slog.w(TAG, "getOverrideLocaleConfig failed", e);
                return null;
            }
        }
    }

    @ProxyMethod("setSystemLocales")
    public static class SetSystemLocales extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // System-wide locale: guests must never be able to change it.
            Slog.w(TAG, "setSystemLocales blocked");
            return null;
        }
    }
}
