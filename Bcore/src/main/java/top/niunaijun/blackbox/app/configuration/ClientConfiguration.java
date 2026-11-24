package top.niunaijun.blackbox.app.configuration;

import java.io.File;

/**
 * Created by Milk on 5/4/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public abstract class ClientConfiguration {

    public boolean isHideRoot() {
        return false;
    }

    public boolean isHideXposed() {
        return false;
    }

    public abstract String getHostPackageName();

    public boolean isEnableDaemonService() {
        return true;
    }

    public boolean isEnableLauncherActivity() {
        return true;
    }

    /**
     * Whether this guest gets direct access to the device's real media storage.
     *
     * <p>By default every guest is sandboxed: its {@code /sdcard} is redirected to
     * {@code blackbox/storage/emulated/<user>}, so a gallery app inside the
     * container sees an empty library even though the HOST holds
     * {@code MANAGE_EXTERNAL_STORAGE}. That is the isolation BlackBox is built for.
     *
     * <p>Returning true here leaves the user-visible media directories
     * ({@code DCIM}, {@code Pictures}, {@code Movies}, {@code Download},
     * {@code Music}) pointing at the real filesystem, and lets the guest query
     * MediaStore. Use it for apps that genuinely need to browse the user's photos.
     *
     * @param packageName the virtual application being started
     */
    public boolean shouldShareMediaStorage(String packageName) {
        return false;
    }

    /**
     * This method is called when an internal application requests to install a new application.
     *
     * @return Is it handled?
     */
    public boolean requestInstallPackage(File file, int userId) {
        return false;
    }
}
