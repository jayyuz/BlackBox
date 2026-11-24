package top.niunaijun.blackbox.fake.service;

import android.app.job.JobInfo;
import android.content.Context;
import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.app.job.BRIJobSchedulerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Created by Milk on 4/2/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class IJobServiceProxy extends BinderInvocationStub {
    public static final String TAG = "JobServiceStub";

    public IJobServiceProxy() {
        super(BRServiceManager.get().getService(Context.JOB_SCHEDULER_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder jobScheduler = BRServiceManager.get().getService("jobscheduler");
        return BRIJobSchedulerStub.get().asInterface(jobScheduler);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    @ProxyMethod("schedule")
    public static class Schedule extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int jobInfoIndex = indexOf(args, JobInfo.class);
            if (jobInfoIndex < 0) {
                return method.invoke(who, args);
            }
            JobInfo jobInfo = (JobInfo) args[jobInfoIndex];
            JobInfo proxyJobInfo = BlackBoxCore.getBJobManager().schedule(jobInfo);
            if (proxyJobInfo == null) {
                // The container could not schedule this job (unresolvable service,
                // process creation failed, ...). Forwarding a null JobInfo makes
                // system_server throw "JobInfo.getService() on a null object
                // reference" inside the caller's process; skipping the call keeps
                // the guest alive at the cost of a job that never runs.
                Slog.w(TAG, "schedule: dropping job " + (jobInfo == null ? null : jobInfo.getId()));
                return null;
            }
            args[jobInfoIndex] = proxyJobInfo;
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("cancel")
    public static class Cancel extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int jobIdIndex = indexOfInt(args);
            if (jobIdIndex < 0) {
                return method.invoke(who, args);
            }
            Integer jobId = (Integer) args[jobIdIndex];
            if (jobId == null) {
                return method.invoke(who, args);
            }
            // The namespace argument, when present, must stay a host-owned name;
            // the virtual package name would be rejected by system_server.
            args[jobIdIndex] = BlackBoxCore.getBJobManager().cancel(currentProcessName(), jobId);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("cancelAll")
    public static class CancelAll extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BlackBoxCore.getBJobManager().cancelAll(currentProcessName());
            return method.invoke(who, args);
        }
    }


    @ProxyMethod("enqueue")
    public static class Enqueue extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int jobInfoIndex = indexOf(args, JobInfo.class);
            if (jobInfoIndex < 0) {
                return method.invoke(who, args);
            }
            JobInfo jobInfo = (JobInfo) args[jobInfoIndex];
            JobInfo proxyJobInfo = BlackBoxCore.getBJobManager().schedule(jobInfo);
            if (proxyJobInfo == null) {
                Slog.w(TAG, "enqueue: dropping job " + (jobInfo == null ? null : jobInfo.getId()));
                return null;
            }
            args[jobInfoIndex] = proxyJobInfo;
            return method.invoke(who, args);
        }
    }

    /**
     * Name of the guest process this job belongs to.
     *
     * <p>AppConfig is null in a process that was never initialised through
     * {@code initProcess()} (for example one revived by the system after a kill),
     * and dereferencing it here crashed the guest inside its own
     * {@code Application.onCreate()}.
     */
    private static String currentProcessName() {
        if (BActivityThread.getAppConfig() != null) {
            return BActivityThread.getAppConfig().processName;
        }
        String processName = BActivityThread.getAppProcessName();
        return processName == null ? BlackBoxCore.getHostPkg() : processName;
    }

    private static int indexOf(Object[] args, Class<?> type) {
        for (int i = 0; i < args.length; i++) {
            if (type.isInstance(args[i])) {
                return i;
            }
        }
        return -1;
    }

    /** Index of the first int parameter: the job id across API levels. */
    private static int indexOfInt(Object[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Integer) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
