package top.niunaijun.blackbox.core.system.pm;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.AtomicFile;

import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.user.BUserHandle;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.utils.CloseUtils;
import top.niunaijun.blackbox.utils.FileUtils;

/**
 * Created by Milk on 4/21/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class BPackageSettings implements Parcelable {
    private static final String TAG = "BPackageSettings";

    /**
     * Settings-file format marker.
     *
     * <p>{@code package.conf} used to be an unversioned Parcel blob, so adding a
     * field silently re-interpreted existing records (and could make the whole
     * file unparseable, which used to delete the app directory). Every settings
     * file now starts with this magic + {@link #VERSION}; readers fall back to the
     * legacy layout when the magic is absent.
     *
     * <p>An int is used rather than a string so that sniffing a legacy file (whose
     * first int is a small component count) can never allocate a bogus string.
     */
    public static final int MAGIC = 0x4242504B; // "BBPK"
    /** 1 = unversioned legacy blob, 2 = magic + version + permission sets. */
    public static final int VERSION = 2;
    /** Sentinel returned by the header sniff for files written before headers existed. */
    private static final int LEGACY_UNKNOWN = 0;

    public BPackage pkg;
    public int appId;
    public InstallOption installOption;
    public Map<Integer, BPackageUserState> userState = new HashMap<>();
    static final BPackageUserState DEFAULT_USER_STATE = new BPackageUserState();

    public BPackageSettings() {
    }

    public List<BPackageUserState> getUserState() {
        return new ArrayList<>(userState.values());
    }

    public List<Integer> getUserIds() {
        return new ArrayList<>(userState.keySet());
    }

    public void setInstalled(boolean inst, int userId) {
        modifyUserState(userId).installed = inst;
    }

    public boolean getInstalled(int userId) {
        return readUserState(userId).installed;
    }

    public boolean getStopped(int userId) {
        return readUserState(userId).stopped;
    }

    public void setStopped(boolean stop, int userId) {
        modifyUserState(userId).stopped = stop;
    }

    public boolean getHidden(int userId) {
        return readUserState(userId).hidden;
    }

    public void setHidden(boolean hidden, int userId) {
        modifyUserState(userId).hidden = hidden;
    }

    public void removeUser(int userId) {
        userState.remove(userId);
    }

    public BPackageUserState readUserState(int userId) {
        BPackageUserState state = userState.get(userId);
        if (state == null) {
            // Unknown user for this package: report as not installed rather than
            // crashing, which is what callers expect (they get a null PackageInfo).
            state = new BPackageUserState();
        }
        state = new BPackageUserState(state);
        // xp模块所有用户可见、如果开启的话
        if (installOption != null && pkg != null && installOption.isFlag(InstallOption.FLAG_XPOSED) &&
                BXposedManagerService.get().isModuleEnable(pkg.packageName) &&
                BXposedManagerService.get().isXPEnable()) {
            state.installed = true;
        }
        if (userId == BUserHandle.USER_ALL) {
            state.installed = true;
        }
        return state;
    }

    /**
     * Returns the LIVE per-user state, creating it if necessary.
     *
     * <p>{@link #readUserState(int)} intentionally hands out a defensive copy,
     * so mutating its result and calling {@link #save()} silently discards the
     * change. Anything that needs to persist a permission grant/denial must use
     * this accessor instead.
     */
    public BPackageUserState modifyUserState(int userId) {
        BPackageUserState state = userState.get(userId);
        if (state == null) {
            state = new BPackageUserState();
            userState.put(userId, state);
        }
        return state;
    }

    public boolean save() {
        synchronized (this) {
            Parcel parcel = Parcel.obtain();
            AtomicFile atomicFile = new AtomicFile(BEnvironment.getPackageConf(pkg.packageName));
            FileOutputStream fileOutputStream = null;
            try {
                writeToParcel(parcel, 0);
                parcel.setDataPosition(0);
                fileOutputStream = atomicFile.startWrite();
                FileUtils.writeParcelToOutput(parcel, fileOutputStream);
                atomicFile.finishWrite(fileOutputStream);
                return true;
            } catch (Throwable e) {
                e.printStackTrace();
                atomicFile.failWrite(fileOutputStream);
                return false;
            } finally {
                parcel.recycle();
                CloseUtils.close(fileOutputStream);
            }
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        writeToParcel(dest, flags, VERSION);
    }

    /**
     * Serialises this record.
     *
     * <p>Version 2 prepends {@link #MAGIC} and the format version so that
     * future field additions can be detected instead of silently mis-parsed.
     */
    public void writeToParcel(Parcel dest, int flags, int version) {
        dest.writeInt(MAGIC);
        dest.writeInt(version);
        dest.writeParcelable(this.pkg, flags);
        dest.writeInt(this.appId);
        dest.writeParcelable(this.installOption, flags);
        dest.writeInt(this.userState.size());
        for (Map.Entry<Integer, BPackageUserState> entry : this.userState.entrySet()) {
            dest.writeValue(entry.getKey());
            dest.writeParcelable(entry.getValue(), flags);
        }
    }

    protected BPackageSettings(Parcel in) {
        // Sniff the header: settings files written by older builds have none.
        this(in, -1);
    }

    /**
     * @param version already-known format version, or {@code -1} to sniff the
     *                magic written by {@link #writeToParcel(Parcel, int, int)}.
     */
    protected BPackageSettings(Parcel in, int version) {
        int dataVersion = version;
        if (dataVersion < 0) {
            dataVersion = readVersionHeader(in);
        }
        readBody(in, dataVersion);
    }

    /**
     * Sniffs the magic + version header.
     *
     * @return the format version, or {@link #LEGACY_UNKNOWN} when the file has no
     * header at all. The parcel position is left right after the header (or at the
     * start of the body for legacy files) so the body can be read immediately.
     */
    private static int readVersionHeader(Parcel in) {
        int start = in.dataPosition();
        try {
            if (in.readInt() == MAGIC) {
                return in.readInt();
            }
        } catch (Throwable ignored) {
        }
        // Not our header: rewind and treat as the legacy, unversioned layout.
        in.setDataPosition(start);
        return LEGACY_UNKNOWN;
    }

    private void readBody(Parcel in, int version) {
        this.pkg = in.readParcelable(BPackage.class.getClassLoader());
        this.appId = in.readInt();
        this.installOption = in.readParcelable(InstallOption.class.getClassLoader());
        int userStateSize = in.readInt();
        if (version >= VERSION) {
            this.userState = readUserStates(in, userStateSize, true);
            if (this.userState == null) {
                throw new IllegalStateException("corrupt package.conf body");
            }
            return;
        }
        // Header-less file. It is either the original layout (3 bytes per user) or
        // the intermediate one that also stored permission lists. Try the richer
        // layout first and keep it only when it parses cleanly, consumes the record
        // exactly and yields plausible user ids, so a bare record cannot be
        // mistaken for a rich one.
        int bodyStart = in.dataPosition();
        Map<Integer, BPackageUserState> rich = readUserStates(in, userStateSize, true);
        if (rich != null && isPlausible(rich) && consumedExactly(in)) {
            this.userState = rich;
            return;
        }
        in.setDataPosition(bodyStart);
        this.userState = readUserStates(in, userStateSize, false);
        if (this.userState == null) {
            // Neither layout fits: let Settings rebuild the record from the APK.
            throw new IllegalStateException("unrecognised package.conf layout");
        }
    }

    /**
     * True when the parcel holds nothing but the record being read.
     *
     * <p>Only meaningful for settings files, which a standalone Parcel is built
     * from; a BPackageSettings travelling inside Binder carries more data after
     * this object. It is used exclusively to disambiguate header-less files, which
     * are always read from disk.
     */
    private static boolean consumedExactly(Parcel in) {
        return in.dataAvail() == 0;
    }

    /**
     * Reads {@code count} per-user entries.
     *
     * @return the parsed map, or null when the bytes do not fit this layout.
     */
    private static Map<Integer, BPackageUserState> readUserStates(Parcel in, int count, boolean withPermissionSets) {
        Map<Integer, BPackageUserState> states = new HashMap<>(count);
        try {
            for (int i = 0; i < count; i++) {
                Integer key = (Integer) in.readValue(Integer.class.getClassLoader());
                // writeParcelable stores the class name, then the body; a null value
                // is stored as a null class name.
                String className = in.readString();
                if (key == null || className == null) {
                    continue;
                }
                states.put(key, new BPackageUserState(in, !withPermissionSets));
            }
        } catch (Throwable e) {
            return null;
        }
        return states;
    }

    private static boolean isPlausible(Map<Integer, BPackageUserState> states) {
        if (states == null) {
            return false;
        }
        for (Integer userId : states.keySet()) {
            // USER_XPOSED (-4) .. USER_ALL (-1) and regular ids.
            if (userId < BUserHandle.USER_XPOSED || userId > 0xFFFF) {
                return false;
            }
        }
        return true;
    }

    public static final Creator<BPackageSettings> CREATOR = new Creator<BPackageSettings>() {
        @Override
        public BPackageSettings createFromParcel(Parcel source) {
            return new BPackageSettings(source);
        }

        @Override
        public BPackageSettings[] newArray(int size) {
            return new BPackageSettings[size];
        }
    };
}
