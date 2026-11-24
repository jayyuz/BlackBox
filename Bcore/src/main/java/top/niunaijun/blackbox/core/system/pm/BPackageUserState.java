package top.niunaijun.blackbox.core.system.pm;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Created by Milk on 4/27/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 *
 * Per-user install state of a virtual package.
 *
 * Note on permissions: this used to only track installed/stopped/hidden, so
 * every runtime permission check fell through to system_server with the HOST's
 * uid. The host can only hold permissions it declared itself, which meant a
 * virtual app could never obtain e.g. RECORD_AUDIO even after the user granted
 * it in the permission dialog. The granted/denied sets below make the
 * virtual permission model actually stateful.
 */
public class BPackageUserState implements Parcelable {
    public boolean installed;
    public boolean stopped;
    public boolean hidden;

    /**
     * Runtime permissions the user has granted to this package for this user.
     * Keyed by permission name; a presence in this set means PERMISSION_GRANTED.
     */
    public final Set<String> grantedPermissions = new HashSet<>();
    /** Runtime permissions the user explicitly denied. */
    public final Set<String> deniedPermissions = new HashSet<>();
    /** Install-time (normal/signature) permissions, resolved during install. */
    public final Set<String> installPermissions = new HashSet<>();

    /**
     * Permissions this guest has actually asked for at runtime (i.e. the user has
     * seen the system dialog for them while inside this container). Used to stop
     * a guest from silently inheriting whatever the host happens to hold.
     */
    public final Set<String> requestedPermissions = new HashSet<>();

    public BPackageUserState() {
        this.installed = false;
        this.stopped = true;
        this.hidden = false;
    }

    public static BPackageUserState create() {
        BPackageUserState state = new BPackageUserState();
        state.installed = true;
        return state;
    }

    public boolean isGranted(String permission) {
        return grantedPermissions.contains(permission) || installPermissions.contains(permission);
    }

    public void grant(String permission) {
        deniedPermissions.remove(permission);
        grantedPermissions.add(permission);
    }

    public void deny(String permission) {
        grantedPermissions.remove(permission);
        deniedPermissions.add(permission);
    }

    public void reset() {
        grantedPermissions.clear();
        deniedPermissions.clear();
        installPermissions.clear();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte(this.installed ? (byte) 1 : (byte) 0);
        dest.writeByte(this.stopped ? (byte) 1 : (byte) 0);
        dest.writeByte(this.hidden ? (byte) 1 : (byte) 0);
        dest.writeStringList(new ArrayList<>(this.grantedPermissions));
        dest.writeStringList(new ArrayList<>(this.deniedPermissions));
        dest.writeStringList(new ArrayList<>(this.installPermissions));
        dest.writeStringList(new ArrayList<>(this.requestedPermissions));
    }

    protected BPackageUserState(Parcel in) {
        this(in, false);
    }

    /**
     * @param legacy true when reading a record produced before permission state
     *               was persisted, i.e. only the three boolean bytes are present.
     */
    protected BPackageUserState(Parcel in, boolean legacy) {
        this.installed = in.readByte() != 0;
        this.stopped = in.readByte() != 0;
        this.hidden = in.readByte() != 0;
        if (legacy) {
            return;
        }

        readPermissionSet(in, this.grantedPermissions);
        readPermissionSet(in, this.deniedPermissions);
        readPermissionSet(in, this.installPermissions);
        readPermissionSet(in, this.requestedPermissions);
    }

    /**
     * Reads one string list defensively.
     *
     * <p>Older builds wrote no lists at all, so a mismatch here means the record
     * is from a previous format rather than corrupt; a partial read is accepted
     * and the caller ends up with an empty set instead of an exception.
     */
    private static void readPermissionSet(Parcel in, Set<String> target) {
        if (in.dataAvail() <= 0) {
            return;
        }
        try {
            ArrayList<String> values = new ArrayList<>();
            in.readStringList(values);
            target.addAll(values);
        } catch (Throwable ignored) {
        }
    }

    public BPackageUserState(BPackageUserState state) {
        this.installed = state.installed;
        this.stopped = state.stopped;
        this.hidden = state.hidden;
        this.grantedPermissions.addAll(state.grantedPermissions);
        this.deniedPermissions.addAll(state.deniedPermissions);
        this.installPermissions.addAll(state.installPermissions);
        this.requestedPermissions.addAll(state.requestedPermissions);
    }

    public static final Parcelable.Creator<BPackageUserState> CREATOR = new Parcelable.Creator<BPackageUserState>() {
        @Override
        public BPackageUserState createFromParcel(Parcel source) {
            return new BPackageUserState(source);
        }

        @Override
        public BPackageUserState[] newArray(int size) {
            return new BPackageUserState[size];
        }
    };
}
