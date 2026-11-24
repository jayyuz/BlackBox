package top.niunaijun.blackbox.utils.provider;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;

import java.io.Serializable;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.compat.ContentProviderCompat;

/**
 * Created by Milk on 4/1/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class ProviderCall {
    /**
     * Retries for a single provider call.
     *
     * <p>Kept small on purpose: {@link #callSafely} is invoked once per virtual
     * service during startup (nine of them), all from Application.onCreate, so the
     * worst case is multiplied by nine. With the previous value of 5 the total
     * reached ~18s in a single thread and the launcher reported the app as
     * unresponsive (ANR) whenever the :black process was unhealthy.
     */
    private static final int DEFAULT_RETRY = 2;

    public static Bundle callSafely(String authority, String methodName, String arg, Bundle bundle) {
        try {
            return call(authority, BlackBoxCore.getContext(), methodName, arg, bundle, DEFAULT_RETRY);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static Bundle call(String authority, Context context, String method, String arg, Bundle bundle, int retryCount) throws IllegalAccessException {
        Uri uri = Uri.parse("content://" + authority);
        return ContentProviderCompat.call(context, uri, method, arg, bundle, retryCount);
    }

    public static final class Builder {

        private Context context;

        private Bundle bundle = new Bundle();

        private String method;
        private String auth;
        private String arg;
        private int retryCount = 5;

        public Builder(Context context, String auth) {
            this.context = context;
            this.auth = auth;
        }

        public Builder methodName(String name) {
            this.method = name;
            return this;
        }

        public Builder arg(String arg) {
            this.arg = arg;
            return this;
        }

        public Builder addArg(String key, Object value) {
            if (value != null) {
                if (value instanceof Boolean) {
                    bundle.putBoolean(key, (Boolean) value);
                } else if (value instanceof Integer) {
                    bundle.putInt(key, (Integer) value);
                } else if (value instanceof String) {
                    bundle.putString(key, (String) value);
                } else if (value instanceof Serializable) {
                    bundle.putSerializable(key, (Serializable) value);
                } else if (value instanceof Bundle) {
                    bundle.putBundle(key, (Bundle) value);
                } else if (value instanceof Parcelable) {
                    bundle.putParcelable(key, (Parcelable) value);
                } else if (value instanceof int[]) {
                    bundle.putIntArray(key, (int[]) value);
                } else {
                    throw new IllegalArgumentException("Unknown type " + value.getClass() + " in Bundle.");
                }
            }
            return this;
        }

        public Builder retry(int retryCount) {
            this.retryCount = retryCount;
            return this;
        }

        public Bundle call() throws IllegalAccessException {
            return ProviderCall.call(auth, context, method, arg, bundle, retryCount);
        }

        public Bundle callSafely() {
            try {
                return call();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
            return null;
        }
    }
}
