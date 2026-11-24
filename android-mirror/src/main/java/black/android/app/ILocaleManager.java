package black.android.app;

import android.os.IBinder;
import android.os.IInterface;

import top.niunaijun.blackreflection.annotation.BClassName;
import top.niunaijun.blackreflection.annotation.BStaticMethod;

/**
 * Mirror for {@code android.app.ILocaleManager}, the binder behind
 * {@code LocaleManager} (API 33+).
 *
 * <p>Only the {@code Stub.asInterface} entry point is needed: BlackBox replaces
 * the registered service binder and answers the calls itself.
 */
@BClassName("android.app.ILocaleManager")
public interface ILocaleManager {
    @BClassName("android.app.ILocaleManager$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder IBinder0);
    }
}
