package top.niunaijun.blackbox.fake.service;

import android.content.pm.PackageInstaller;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.ParceledListSliceCompat;

/**
 * Wraps the real {@code IPackageInstaller} returned by
 * {@code IPackageManager.getPackageInstaller()}.
 *
 * <p>Session queries are bound to the <em>caller's</em> package name: system_server
 * runs {@code AppOpsManager.checkPackage(Binder.getCallingUid(), packageName)} and
 * throws when they do not match. A guest runs under the HOST's uid but passes its
 * own package name, so every such call fails with
 *
 * <pre>
 * SecurityException: Package org.fdroid.fdroid does not belong to 10470
 * </pre>
 *
 * <p>Guests cannot legitimately own install sessions anyway - installs inside the
 * container go through {@code BPackageManagerService} - so session enumeration is
 * answered with an empty list instead of being forwarded.
 *
 * <p>The interface is hidden, so everything goes through reflection rather than a
 * compile-time type.
 */
public final class PackageInstallerStub implements InvocationHandler {
    private static final String TAG = "PackageInstallerStub";
    private static final String INTERFACE_NAME = "android.content.pm.IPackageInstaller";

    private final Object mBase;

    private PackageInstallerStub(Object base) {
        mBase = base;
    }

    /**
     * @return a proxy exposing the same interfaces as {@code base}, or {@code base}
     * itself when the interface cannot be resolved.
     */
    public static Object wrap(Object base) {
        if (base == null) {
            return null;
        }
        try {
            Class<?> iface = Class.forName(INTERFACE_NAME, false, base.getClass().getClassLoader());
            if (!iface.isInstance(base)) {
                return base;
            }
            return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    new PackageInstallerStub(base));
        } catch (Throwable e) {
            Slog.w(TAG, "unable to wrap IPackageInstaller", e);
            return base;
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        switch (name) {
            case "getMySessions":
            case "getAllSessions":
            case "getStagedSessions":
                // Never forwarded: the caller-supplied package name is the guest's
                // and can never match the host uid that actually placed the call.
                return emptySessionList(method);
            case "getSessionInfo":
            case "openSession":
            case "abandonSession":
            case "setPermissionsResult":
                // Same ownership check, but there is no meaningful "empty" answer;
                // report the session as absent so the guest takes its error path
                // instead of dying on a SecurityException.
                return defaultValue(method);
            default:
                break;
        }
        try {
            return method.invoke(mBase, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() == null ? e : e.getCause();
        }
    }

    private static Object emptySessionList(Method method) {
        Class<?> returnType = method.getReturnType();
        if (returnType == List.class) {
            return new ArrayList<>();
        }
        // Session lists travel as ParceledListSlice across the binder.
        return ParceledListSliceCompat.create(new ArrayList<>());
    }

    private static Object defaultValue(Method method) {
        Class<?> type = method.getReturnType();
        if (type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            // Session ids are "not found" when negative.
            return PackageInstaller.STATUS_FAILURE;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
