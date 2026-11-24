package top.niunaijun.blackbox.core.env;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;

/**
 * Installs the hidden-API exemptions BlackBox needs in order to drive the
 * framework from a normal application process.
 *
 * <p>The virtualisation strategy relies on reaching non-SDK members such as
 * {@code ActivityThread#mH}, {@code ActivityThread$H#EXECUTE_TRANSACTION},
 * {@code ActivityThread$ActivityClientRecord#activity} and
 * {@code LoadedApk#mSecurityViolation}. Since Android 9 those are blocked by
 * the hidden API policy, and on API 28+ the restriction applies per process,
 * so it has to be lifted in the host process, the {@code :black} process and
 * every virtual app process independently.
 *
 * <p>{@code me.weishu:free_reflection} only unseals the hidden API from
 * Android P's light-grey list; it cannot help with the dark-grey/black lists
 * that later releases use, hence the additional {@code HiddenApiBypass} pass.
 */
public final class HiddenApiCompat {
    private static final String TAG = "HiddenApiCompat";

    /** Matches every framework member and everything under the BlackBox package. */
    private static final String ALL_PREFIX = "L";

    private HiddenApiCompat() {
    }

    public static void install() {
        // unseal() above already handled the legacy (API 28) path; the bypass is
        // what actually makes a difference from API 29 onwards.
        if (!BuildCompat.isPie()) {
            return;
        }
        try {
            // An empty exemption prefix list means "exempt everything".
            if (!HiddenApiBypass.addHiddenApiExemptions(ALL_PREFIX)) {
                Slog.w(TAG, "addHiddenApiExemptions returned false");
            }
        } catch (Throwable e) {
            // Never let this be fatal: on exotic ROMs the bypass may not apply,
            // and the existing free_reflection path is still better than nothing.
            Slog.w(TAG, "unable to install hidden API exemptions", e);
        }
    }
}
