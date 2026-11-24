# 从 Android 12 迁移到 Android 16：一次实战记录

> 记录把 BlackBox 从 `compileSdk 30 / AGP 4.0.1 / Gradle 6.5` 迁移到 `compileSdk 36 / AGP 8.13.2 / Gradle 8.13` 的全过程，以及在 Android 13–16 上逐个击破的兼容性问题。
>
> 目标设备：Xiaomi M2011K2G，Android 14 (API 34)，arm64-v8a。
> 说明：Android 15/16 的行为差异通过比对 AOSP `android15-release` / `android16-release` 分支源码确认，未在真机上验证。

---

## 零、先说结论

| 类别 | 数量 | 说明 |
|---|---|---|
| 构建系统迁移 | 6 项 | AGP/Gradle/JDK/namespace/依赖仓库 |
| 框架 API 破坏性变更 | 6 项 | 会导致功能失效或崩溃 |
| 权限模型缺失 | 3 项 | 原项目根本未实现，非版本问题 |
| 原生层适配 | 4 项 | NDK 升级暴露的既有缺陷 |
| 构建工具链问题 | 3 项 | AIDL/ABI/minSdk |

其中**最隐蔽的三个**：

1. `getLaunchingActivity()` 在 Android 13 被删除，导致虚拟 Activity 内容从未生效，表现为**无限重启循环**；
2. `checkPermissionForDevice()` 从未被 hook，导致**所有运行时权限**失效（不只是录音）；
3. 隐藏 API 限制未处理，导致 `:black` 进程**启动即崩溃**。

---

## 一、基线：先让项目能编译

原项目处于"已停滞"状态：`jcenter()` 已下线，`gradle-6.5` 下载不动。第一件事是确认**原样能否构建**。

```bash
./gradlew :Bcore:assembleDebug
# UnknownHostException: services.gradle.org   ← 卡在下载
```

### 1.1 工具链升级

| 项目 | 迁移前 | 迁移后 | 原因 |
|---|---|---|---|
| AGP | 4.0.1 | **8.13.2** | 支持 compileSdk 36 |
| Gradle | 6.5 | **8.13** | AGP 8 强制要求 8.x |
| JDK | 8 | **17** | AGP 8 强制要求 17+ |
| compileSdk | 30 | **36** | — |
| targetSdk | 30 | **36** | — |
| buildTools | 28.0.2 | **36.0.0** | — |
| Kotlin | 1.4.31 | **1.9.23** | 与 AGP 8 匹配 |

```groovy
// build.gradle
buildscript {
    ext.kotlin_version = "1.9.23"
    dependencies {
        classpath "com.android.tools.build:gradle:8.13.2"
        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version"
    }
}
ext {
    compileSdkVersion = 36      // Android 16
    buildToolsVersion = "36.0.0"
    targetSdkVersion  = 36
}
```

### 1.2 jcenter 下线导致的依赖缺失

`jcenter()` 已经彻底关闭，以下依赖**无法再解析**：

| 依赖 | 状态 | 处理 |
|---|---|---|
| `me.weishu:free_reflection:3.0.1` | Maven Central 404 | 保留（阿里云镜像可用） |
| `com.imuxuan:floatingview:1.6` | 404 | **本地重写** |
| `com.roger.catloadinglibrary:catloadinglibrary:1.0.9` | 404 | **本地重写** |
| `com.github.nukc:StateView` | 需换坐标 | `com.github.nukc:StateView:2.2.0` |

前两个悬浮窗/加载框库没有可用替代，直接按原 API 实现了轻量版本：

```kotlin
// app/src/main/java/com/imuxuan/floatingview/FloatingView.kt
// 保持 FloatingView.get().customView() / attach() / detach() 不变，
// 内部改用 TYPE_APPLICATION_OVERLAY + 磁吸边缘
class FloatingMagnetView(context: Context) : FrameLayout(context) { ... }
```

```kotlin
// app/src/main/java/com/roger/catloadinglibrary/CatLoadingView.kt
class CatLoadingView : DialogFragment() {
    fun setBackgroundColor(color: Int)          // 保持原签名，兼容资源 id
    fun setClickCancelAble(cancelAble: Boolean)
}
```

好处是调用侧 `RockerManager.kt` / `LoadingActivity.kt` **一行都不用改**。

### 1.3 AGP 8 的强制要求

**① 必须显式声明 `namespace`**

AGP 8 移除了对 manifest 中 `package` 属性的支持。所有 8 个模块都要补：

```groovy
android {
    namespace 'top.niunaijun.blackbox'
    // 同时从 AndroidManifest.xml 里删掉 package="..."
}
```

**② `lintOptions` → `lint`，`check()` 已移除**

```groovy
// 旧
lintOptions { check 'NewApi', 'InlinedApi' }
// 新（check() 方法在 AGP 8 已删除，用了会直接构建失败）
lint { checkReleaseBuilds false; abortOnError false }
```

**③ `aidlPackageWhiteList` → `aidlPackagedList`**

```groovy
android {
    aidlPackagedList "android/app/IServiceConnection.aidl"
    aidlPackagedList "android/accounts/IAccountManagerResponse.aidl"
}
```

**④ 产品风味必须声明 `dimension`**，`compileSdkVersion` 改 `compileSdk`。

### 1.4 AIDL 包名不匹配

```
ERROR: BLocationConfig should be declared in a file called
       top/niunaijun/blackbox/core/system/location/BLocationConfig.aidl
```

`Bcore/src/main/aidl/.../entity/location/BLocationConfig.aidl` 里写的 `package` 是 `core.system.location`，与目录不符。旧版 AIDL 工具容忍这种不一致，新版严格校验。

```java
// 修正为与目录一致
package top.niunaijun.blackbox.entity.location;
parcelable BLocationConfig;
```

### 1.5 ABI 收敛

NDK r27 编译时暴露两个既存缺陷：

```
FATAL: Utils/fake_dlfcn.cpp:24: error: "Arch unknown, please port me"
FATAL: trampoline/extras.h:22: error: use of undeclared identifier 'ReleaseLock'
```

- `fake_dlfcn.cpp` 只有 `__arm__` / `__i386__` / `__aarch64__` 分支，**没有 x86_64**；
- pine 的 x86 trampoline 上游本身不完整：`Extras::CloneAndUnlock()` 调用的 `ReleaseLock()` 只在 `__arm__`/`__aarch64__` 下编译。

于是 ABIs 从 `armeabi-v7a, arm64-v8a, x86` 收敛为前两个（与原项目"32/64 位两个独立包"的设计一致）：

```groovy
ndk { abiFilters 'armeabi-v7a', 'arm64-v8a' }
```

### 1.6 NDK r27 的额外严格性

```cpp
// Bcore/pine-core/src/main/cpp/utils/scoped_local_ref.h
#include "macros.h"
#include <cstddef>       // ← 新增：NDK r27 不再传递性提供 std::nullptr_t
```

armv7 的汇编还需要显式 `-fPIC`，并给数据符号加 `.hidden`：

```cmake
# CMakeLists.txt
set(CMAKE_ASM_FLAGS "${CMAKE_ASM_FLAGS} -arch armv7 -fPIC")
```

```asm
; thumb2.S —— 否则报 R_ARM_THM_PC12 cannot be used ... recompile with -fPIC
#define VAR(name) \
.global name; \
.hidden name; \
name:
```

### 1.7 app 模块的 API 漂移

依赖升级后 Kotlin 侧有 11 处编译错误，集中在三类：

| 模式 | 旧写法 | 新写法 |
|---|---|---|
| `ViewModelProvider` 泛型收紧 | `<T : ViewModel?> create(...)` | `<T : ViewModel> create(...)` |
| 平台类型不再容忍 null | `onOptionsItemSelected(item: MenuItem?)` | `item: MenuItem` |
| `Bundle?` → `Bundle` | `onActivitySaveInstanceState(_, outState: Bundle?)` | `outState: Bundle` |

```kotlin
// XpRepository.kt —— versionName 现在是可空的
it.packageInfo?.versionName ?: ""
```

---

## 二、运行时崩溃：隐藏 API

构建通过后第一次运行，`:black` 进程直接崩溃：

```
FATAL EXCEPTION: main
Process: top.niunaijun.blackboxa64:black
java.lang.NullPointerException: Attempt to invoke virtual method
    'int java.lang.Integer.intValue()' on a null object reference
    at HCallbackProxy.handleMessage(HCallbackProxy.java:81)
```

### 2.1 根因

第 81 行读的是：

```java
msg.what == BRActivityThreadH.get().EXECUTE_TRANSACTION()
```

`EXECUTE_TRANSACTION` 是 `ActivityThread$H` 的静态字段，属于隐藏 API。原项目只用了 `me.weishu:free_reflection`，而它**只处理 Android 9 的灰名单**，对深灰/黑名单无效 → 反射失败 → 返回 `null` → 自动拆箱 NPE。

### 2.2 修复

引入 `org.lsposed.hiddenapibypass`，在每个进程独立豁免：

```gradle
implementation 'org.lsposed.hiddenapibypass:hiddenapibypass:6.1'
```

```java
// Bcore/.../core/env/HiddenApiCompat.java
public static void install() {
    if (!BuildCompat.isPie()) return;
    try {
        // 空前缀 = 豁免所有
        HiddenApiBypass.addHiddenApiExemptions("L");
    } catch (Throwable e) {
        Slog.w(TAG, "unable to install hidden API exemptions", e);
    }
}
```

关键点：**必须在每个进程各调一次**。隐藏 API 限制是进程级的，宿主、`:black`、`:pN` 互不共享。

```java
// BlackBoxCore.doAttachBaseContext()
Reflection.unseal(context);     // 旧的，仅覆盖 API 28 灰名单
HiddenApiCompat.install();      // 新增
```

这一处修复直接解决了崩溃 —— 说明**隐藏 API 是本项目在 Android 9+ 上运行的前提条件**，原项目在这块是缺失的。

---

## 三、致命问题：虚拟 Activity 从未真正启动

接下来启动 QQ 时出现**无限重启**：`ProxyActivity.onCreate` 被反复调用，界面一直黑屏。

```
ActivityTaskManager: START ... ProxyActivity$P0
AppInstrumentation: callActivityOnCreate: ProxyActivity$P0
ProxyActivity: onCreate          ← 反复数十次
```

### 3.1 定位

`HCallbackProxy.handleLaunchActivity()` 的 Android 12+ 分支：

```java
// 修复前
if (BuildCompat.isS()) {
    Object record = BRActivityThread.get(mainThread).getLaunchingActivity(token);
    ActivityThreadActivityClientRecordContext ctx = BRActivityThreadActivityClientRecord.get(record);
    ctx._set_intent(stubRecord.mTarget);          // ← record 为 null，静默失败
    ctx._set_activityInfo(activityInfo);
}
```

两个独立问题叠加：

**① `getLaunchingActivity()` 在 Android 13 被删除**

| 版本 | 方法 |
|---|---|
| Android 12 | `getLaunchingActivity(IBinder)` |
| Android 13+ | `getActivityClient(IBinder)` |

**② 即使版本对，时序也不对**

AOSP `ActivityThread.handleLaunchActivity()` 内部才有 `mActivities.put(r.token, r)`。而我们的 `Handler.Callback` 在它**之前**执行 —— 此时按 token 查必然返回 `null`。

### 3.2 修复：改"请求"而不是改"结果"

`ClientTransaction` 里的 `LaunchActivityItem` 是**请求**，`ActivityClientRecord` 是**结果**。改请求既无时序依赖，也无需版本分支：

```java
// HCallbackProxy.java
if (BuildCompat.isPie()) {
    LaunchActivityItemContext item = BRLaunchActivityItem.get(r);
    item._set_mIntent(stubRecord.mTarget);
    item._set_mInfo(activityInfo);
} else {
    // Android 9 之前仍是 ActivityClientRecord
}
```

同时也修掉了 `getLaunchActivityItem()` 里的空指针隐患：

```java
// 修复前：getCanonicalName() 对匿名类返回 null → NPE
if (BRLaunchActivityItem.getRealClass().getName().equals(obj.getClass().getCanonicalName()))

// 修复后
if (launchItemName != null && launchItemName.equals(objClass.getName()))
```

### 3.3 类加载器的兜底

修完这个后出现新崩溃：

```
ClassNotFoundException: Didn't find class "ProxyActivity$P0"
  on path: DexPathList[[zip file ".../com.guassianblur/base.apk"]]
```

`AppInstrumentation.newActivity()` 拿到的 `ClassLoader` 是 **guest 的**，其 dex 路径只含 guest APK，而 `ProxyActivity$P0` 在**宿主** APK 里。补一层宿主 ClassLoader 兜底：

```java
public Activity newActivity(ClassLoader cl, String className, Intent intent) {
    try {
        return super.newActivity(cl, className, intent);
    } catch (ClassNotFoundException e) {
        try {
            return super.newActivity(BlackBoxCore.getContext().getClassLoader(), className, intent);
        } catch (ClassNotFoundException ignored) {
            return mBaseInstrumentation.newActivity(cl, className, intent);
        }
    }
}
```

至此虚拟应用能正常启动并渲染。

---

## 四、QQ 启动即退出：`bindServiceInstance`

安装 QQ 后点击图标，进程立刻死亡：

```
java.lang.SecurityException: Not allowed to bind to service
  Intent { cmp=com.tencent.mobileqq/...RaftKitProcessManagerService$RaftKitProcess }
    at android.app.ContextImpl.bindServiceCommon(ContextImpl.java:2133)
```

### 4.1 根因

对比 AOSP 源码发现，`ContextImpl.bindServiceCommon()` 在 Android 13 之后**不再调用 `bindService`**：

```java
// android14-release/core/java/android/app/ContextImpl.java:2126
int res = ActivityManager.getService().bindServiceInstance(
        mMainThread.getApplicationThread(), getActivityToken(), service,
        service.resolveTypeIfNeeded(getContentResolver()),
        sd, flags, instanceName, getOpPackageName(), user.getIdentifier());
```

而 BlackBox 只 hook 了 `bindService` 和 `bindIsolatedService`。`bindServiceInstance` 从未被拦截 → 请求直接打到真实 system_server → 越权绑定失败。

### 4.2 修复

```java
// IActivityManagerProxy.java
@ProxyMethod("bindServiceInstance")
public static class BindServiceInstance extends BindService {
    @Override
    protected Object beforeHook(Object who, Method method, Object[] args) {
        // (caller, token, service, resolvedType, connection, flags,
        //  instanceName, callingPackage, userId)
        if (args.length > 6) args[6] = null;   // 虚拟应用不支持 service instance
        return super.beforeHook(who, method, args);   // service/connection 索引相同，复用
    }
}
```

顺带修了 `ActiveServices.bindService()` 的一个隐患 —— 解析失败时**返回原 Intent**，等于把 guest 的组件名透传给系统：

```java
// 修复前
if (resolveInfo == null) return intent;     // ← 透传给 system_server，直接 SecurityException
// 修复后
if (resolveInfo == null) return null;       // 明确的"无法解析"
```

修复后 QQ 能正常启动到登录页。

---

## 五、录音失败：权限模型的三重缺陷

用户反馈"QQ 登录成功，录音权限也点了允许，但录不了音"。

### 5.1 诊断过程

一开始看到的是这段日志：

```
AndroidPermissionHandler: requestAndroidPermissions.
    grantedPermissions: [] needRequestPermissions: [android.permission.RECORD_AUDIO]
QQPermission: onAllGranted          ← 授权流程本身是成功的
```

权限对话框每次长按都弹出（累计 16 次），说明 QQ 每次都认为"无权限"。

但宿主侧检查，权限明明已经授予：

```bash
adb shell dumpsys package top.niunaijun.blackboxa64 | grep RECORD_AUDIO
# android.permission.RECORD_AUDIO: granted=true, flags=[ USER_SET|... ]
```

> **踩坑记录**：`PermissionManager.PERMISSION_GRANTED = 0`，`PERMISSION_DENIED = -1`。
> 我最初看到日志里返回 `-1` 还以为是 GRANTED，实际是 DENIED，白白多排查了好几轮。

### 5.2 缺陷一：`checkPermissionForDevice` 从未被 hook

反编译 QQ（`dexdump`）确认它的调用链：

```
Context.checkSelfPermission(perm)
  → PermissionManager.checkPermission(perm, pid, uid)
    → IActivityManager.checkPermissionForDevice(perm, pid, uid, deviceId)
```

对比 AOSP：

```aidl
// android16-release/core/java/android/app/IActivityManager.aidl:967
int checkPermissionForDevice(in String permission, int pid, int uid, int deviceId);
```

**BlackBox 只 hook 了 `checkPermission`，没有 `checkPermissionForDevice`**。于是权限判定完全绕过虚拟化层，直接问"宿主有没有这个权限"。

而且这是**全局性问题** —— 相机、定位、通讯录、存储所有运行时权限都走同一条路径，全部失效。录音只是最先被用户发现的那个。

### 5.3 缺陷二：`replaceLastUid()` 改错了参数

原有代码用 `MethodParameterUtils.replaceLastUid(args)` 把最后一个 `Integer` 改成宿主 uid。但两个方法签名不同：

```java
checkPermission(perm, pid, uid)                  // 最后一个 Integer 是 uid ✅
checkPermissionForDevice(perm, pid, uid, deviceId) // 最后一个是 deviceId ❌
```

结果把 deviceId 改成了 uid 值，system_server 收到非法参数，**恒返回 DENIED**。

修复：按位置精确取 uid（两个方法里 uid 都紧跟 pid）：

```java
static int indexOfUid(Object[] args) {
    int seen = 0;
    for (int i = 0; i < args.length; i++) {
        if (args[i] instanceof Integer) {
            if (++seen == 2) return i;   // 0 = pid, 1 = uid
        }
    }
    return -1;
}
```

### 5.4 缺陷三：判定逻辑跑在错误进程

`BPackageManagerService` 运行在 `:black` 进程，而 `BActivityThread.getAppPackageName()` 只在 **guest 进程**有值。服务端调用时恒为 `null` → 直接返回 DENIED。

修复：由 client 侧传入包名。

```java
// AIDL
int checkPermission(String permission, boolean hostGranted, int userId, String guestPackage);
```

```java
// BPackageManager（client）
return getService().checkPermission(permission, hostGranted, userId,
        BActivityThread.getAppPackageName());
```

### 5.5 缺陷四：权限状态根本没有存储

这是最根本的 —— `BPackageUserState` 原来只有三个字段：

```java
public boolean installed;
public boolean stopped;
public boolean hidden;
```

**没有任何权限授权状态**。也就是说这个项目从未实现过虚拟应用的运行时权限模型。`PackageManagerCompat` 里本该填权限标志的代码也是注释掉的：

```java
// 原代码
// pi.requestedPermissionsFlags[i] |= PackageInfo.REQUESTED_PERMISSION_REQUIRED;
// if (grantedPermissions != null && grantedPermissions.contains(perm)) {
//     pi.requestedPermissionsFlags[i] |= PackageInfo.REQUESTED_PERMISSION_GRANTED;
// }
```

补全后的 `BPackageUserState`：

```java
public final Set<String> grantedPermissions = new HashSet<>();   // 用户已授予
public final Set<String> deniedPermissions  = new HashSet<>();   // 用户已拒绝
public final Set<String> installPermissions = new HashSet<>();   // 安装期权限
public final Set<String> requestedPermissions = new HashSet<>(); // 实际申请过的
```

`requestedPermissions` 的用途是防止**越权继承**：只有当 guest 真的弹过对话框申请过某权限，才接受宿主侧的授权结果。否则 guest 会静默继承宿主持有的所有权限。

### 5.6 一个隐蔽的写入丢失

```java
// BPackageSettings.java
public BPackageUserState readUserState(int userId) {
    BPackageUserState state = userState.get(userId);
    if (state == null) state = new BPackageUserState();
    state = new BPackageUserState(state);     // ← 返回的是副本！
    return state;
}
```

`readUserState()` 返回防御性副本。我最初的授权代码 `readUserState().grant(...)` 改的是副本，`save()` 完全无效。新增 `modifyUserState()` 返回活对象：

```java
public BPackageUserState modifyUserState(int userId) {
    BPackageUserState state = userState.get(userId);
    if (state == null) {
        state = new BPackageUserState();
        userState.put(userId, state);        // ← 真正写回 map
    }
    return state;
}
```

### 5.7 递归陷阱

在 `checkPermission` 的 hook 里"问真实系统"，很容易踩到递归：

```java
// ❌ 无限递归
Context.checkPermission()  →  PermissionManager  →  IActivityManager.checkPermission
                                                    ↑ 又回到自己的 hook
```

症状很隐蔽：`StackOverflowError` 被 `catch` 吞掉，最终返回 DENIED。

正确做法是直接用**原始 binder 调用**（`method.invoke(who, args)`），不经过 `Context`/`PackageManager` 的封装层。

### 5.8 一个我自己引入的回归

修完权限后启动崩溃：

```
SecurityException: Calling uid: 10470 doesn't match source uid: 10002
    at ContentProviderStub.invoke
```

因为我顺手把 `AttributionSource` 的 uid 改成了虚拟 uid。但 binder 调用的真实发起方是宿主（10470），system_server 会校验两者一致：

```java
// 修复前
ContextCompat.fixAttributionSourceState(arg, BActivityThread.getBUid());   // 虚拟 uid
// 修复后
ContextCompat.fixAttributionSourceState(arg, android.os.Process.myUid());  // 真实 uid
```

### 5.9 验证结果

```
[pcm][slice] audio=4200..4300ms unreadMs=0
[asr][submit] pcmBytes=3200 pcmDurationMs=100 silkSize=138
[asr][submit] format=SILK file=tmp_q_bao_voice-..._0_42.silk
```

麦克风连续采集 4.3 秒，编码为 SILK 并送语音识别。

| 指标 | 修复前 | 修复后 |
|---|---|---|
| 权限弹框次数 | 16 | **0** |
| `needRequestPermissions` | 持续出现 | **0** |
| 录音采集 | 无 | **4.3 秒 PCM** |
| 崩溃 | — | **0** |

权限判定也验证正确：宿主已授权 → `0`(GRANTED)；未授权 → `-1`(DENIED)。

### 5.10 权限模型重构的副作用：`package.conf` 格式漂移

`BPackageUserState` 从 3 个布尔字段扩展成 3 个布尔 + 4 个权限集合后，`package.conf` 的 Parcel 布局变了，但**文件本身没有任何版本标识**。

后果链条很隐蔽：

```
旧版本写出的 package.conf（3 byte / entry）
        ↓ 新代码按新布局解析 → 读到下一个 entry 的 key / 类名
解析异常 → Settings.updatePackageLP 的 catch
        ↓
FileUtils.deleteDir(app)  ← 直接把 guest 删了
        ↓
进程/服务再启动 → getPackageInfo 返回 null → handleBindApplication NPE
```

外层表现是一个看起来毫不相关的崩溃：

```
java.lang.RuntimeException: Unable to bind to service ProxyService$P17
Caused by: NullPointerException: Attempt to read from field
  'android.content.pm.ApplicationInfo android.content.pm.PackageInfo.applicationInfo'
  on a null object reference
    at BActivityThread.handleBindApplication(BActivityThread.java:306)
```

三处修复（都落在"失败必须可见"和"失败不得销毁数据"两条原则上）：

1. **格式带版本号**。`BPackageSettings.writeToParcel()` 前面加 `MAGIC` + `VERSION`；读取时嗅探 header，没有 header 就按旧布局解析，并区分"只有 3 个字节"和"3 字节 + 权限列表"两种历史格式（用 `dataAvail()==0` 和 userId 合法性双重校验）。
2. **解析失败不再删包**。先尝试从磁盘上的 `base.apk` 重建 `BPackageSettings`（并按 `data/user/<id>/<pkg>` 目录恢复"哪些用户装过"），重建也失败才认定包真的坏了。
3. **空值不再静默传递**。`generatePackageInfo` 里 `catch (Throwable ignored)` 改成打日志；`applicationInfo` 为 null 时宁可返回 null 也不返回半个 `PackageInfo`（调用方都会立刻解引用它）；`Settings` 里 `pkg.applicationInfo = generateApplicationInfo(...)` 只在非 null 时赋值。

顺带修掉同类问题的其他入口：`checkUseInstalledOrHidden()` 对 `appInfo == null` 判空，`BXposedManagerService` 的 `mXposedConfig` 解析失败时回退到空配置而不是留 null。

### 5.11 `userId` 不能从进程态猜

`handleBindApplication()` 原来用 `BActivityThread.getUserId()`，而它在 `mAppConfig == null` 时会退化成 `0`：

```java
public static int getUserId() {
    return getAppConfig() == null ? 0 : getAppConfig().userId;
}
```

服务进程是惰性绑定的 —— `ProxyContentProvider` 拉起进程时只调 `initProcess(appConfig)`，`mBoundApplication` 仍是 null，要等第一次 `onBind`/`onStartCommand` 才走 `bindApplication`。如果进程被系统回收后复用、`initProcess` 没再执行，这里就会拿 user 0 去查一个装在虚拟用户 1 上的包，`getPackageInfo` 直接返回 null。

修复是把 `userId` 从 `ProxyServiceRecord` 一路传下来（它本来就带着 `mUserId`），进程态只作为兜底：

```java
public static final int UNKNOWN_USER = Integer.MIN_VALUE;   // 而非 -1，-1 是合法的 USER_ALL

int userId = resolveUserId(requestUserId);   // record 优先，AppConfig 兜底
```

`-1` 不能当哨兵：`BUserHandle.USER_ALL` 就是 `-1`，用它做"未提供"会和真实值混淆。

---

## 六、原生层：`@CriticalNative` 与 libcore 变更

### 6.1 `canonicalize0` 签名变更（Android 15）

文件重定向依赖 hook `java.io.UnixFileSystem.canonicalize0`。但 Android 15 改了这个方法：

```java
// android14-release
private native String canonicalize0(String path) throws IOException;

// android15-release / android16-release
private String canonicalize0(String path) throws IOException {
    boolean isAtLeastTargetSdk35 = VMRuntime.getSdkVersion() >= VANILLA_ICE_CREAM && ...;
    return canonicalize0(path, isAtLeastTargetSdk35);     // ← 单参版本变成普通 Java 方法
}
private native String canonicalize0(String path, boolean isAtLeastTargetSdk35);
```

旧签名在 API 35+ 上无法解析 → **IO 重定向在路径规范化环节失效**。同时 hook 两个签名：

```cpp
// Android < 15
JniHook::HookJniFun(env, "java/io/UnixFileSystem", "canonicalize0",
                    "(Ljava/lang/String;)Ljava/lang/String;", ...);
// Android >= 15
JniHook::HookJniFun(env, "java/io/UnixFileSystem", "canonicalize0",
                    "(Ljava/lang/String;Z)Ljava/lang/String;", ...);
```

`HookJniFun` 在方法不存在时只打日志返回，所以可以无条件尝试。

### 6.2 `Binder.getCallingUid` 是 `@CriticalNative`

这是最容易崩的一处。`getCallingUid` 从 Android 10 起带 `@CriticalNative`：

```java
@CriticalNative
public static final native int getCallingUid();
```

`@CriticalNative` 意味着 ART 生成的跳转桩**不传 `JNIEnv*` 和 `jclass`**，直接按声明参数调用。而我们的替换函数是普通 JNI 签名：

```cpp
HOOK_JNI(jint, getCallingUid, JNIEnv *env, jobject obj)   // 期待 env 在第一个参数
```

如果不清理这个标志位，hook 会被以**错误的调用约定**触发，`env` 拿到的是垃圾指针。

修复：

```cpp
// JniHook.cpp
inline static void ClearCriticalNativeFlag(char *art_method) {
    ClearAccessFlag(art_method, kAccCriticalNative);
}

bool CheckFlags(void *artMethod) {
    char *method = static_cast<char *>(artMethod);
    if (!HasAccessFlag(method, kAccNative)) return false;
    ClearFastNativeFlag(method);
    ClearCriticalNativeFlag(method);   // ← 新增，所有 API 级别都需要
    return true;
}
```

### 6.3 `ArtMethod` 偏移探测的边界错误

原代码混用了"字节数"和"槽位数"：

```cpp
// 修复前
HookEnv.art_method_size = (size_t) nativeOffset2 - (size_t) nativeOffset;   // 字节数
for (int i = 0; i < HookEnv.art_method_size; ++i) {          // ❌ 用字节数当槽位上限
    if ((void*)artMethod[i] == native_offset) { ... }
}
```

`artMethod` 是 `uintptr_t*`，索引 `i` 已经是槽位，上限应为 `size / sizeof(uintptr_t)`。原代码多扫描约 8 倍，虽然靠 `break` 侥幸命中，但一旦偏移出现在末尾就会越界读。

```cpp
// 修复后
HookEnv.art_method_size = (size_t) ((char *) nativeOffset2 - (char *) nativeOffset);
const size_t slots = HookEnv.art_method_size / sizeof(uintptr_t);
for (size_t i = 0; i < slots; ++i) { ... }
if (HookEnv.art_method_native_offset == 0) {
    ALOGE("JniHook: unable to locate ArtMethod::data_ (native offset)");
}
```

最后那个检查很重要：偏移为 0 会让 `HookJniFun` **静默**跳过整个进程的所有 hook，非常难排查。

### 6.4 Android 14 的运行时强制项

**① 前台服务类型（API 34）**

```java
// 缺失会抛 MissingForegroundServiceTypeException
if (BuildCompat.isUpsideDownCake()) {
    startForeground(NOTIFY_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
}
```

```xml
<service android:name=".DaemonService"
    android:foregroundServiceType="dataSync|specialUse" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
```

**② 运行时注册的 receiver 必须显式声明 `exported`（API 34）**

```java
// BroadcastManager —— 需要接收跨进程广播，用 EXPORTED
if (Build.VERSION.SDK_INT >= 33) {
    registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
}
// BPackageManagerService —— 只听系统包广播，用 NOT_EXPORTED
registerReceiver(handler, filter, Context.RECEIVER_NOT_EXPORTED);
```

**③ `Handler.handleNewIntent` 的参数类型变更（API 31）**

```java
// Android 11 及以前
void handleNewIntent(IBinder token, List<ReferrerIntent> intents);
// Android 12 起
void handleNewIntent(ActivityClientRecord r, List<ReferrerIntent> intents);
```

原代码传 `IBinder`，在 Android 12+ 上静默失效 → **应用被重新拉起时不触发 `onNewIntent`**。改为先按 token 查 record，再精确匹配 2 参重载。

### 6.5 现代化权限声明

Android 12–14 新增的权限，宿主必须先声明，否则 system_server 在包层面就拒绝，用户连对话框都看不到：

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
<uses-permission android:name="android.permission.ACCESS_MEDIA_LOCATION" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
```

### 6.6 `IPackageManager` 参数从 int 变 long（API 33）

这个会直接抛 `ClassCastException`：

```
java.lang.ClassCastException: java.lang.Long cannot be cast to java.lang.Integer
    at IPackageManagerProxy$GetApplicationInfo.hook
```

```aidl
// android12-release
ApplicationInfo getApplicationInfo(String packageName, int flags, int userId);
// android13-release 起
ApplicationInfo getApplicationInfo(String packageName, long flags, int userId);
```

受影响的还有 `getPackageInfo`、`getActivityInfo`、`resolveIntent`、`getInstalledPackages` 等，共 12 处。统一用容错取值：

```java
public static int getFlags(Object[] args, int index) {
    Object v = args[index];
    if (v instanceof Integer) return (Integer) v;
    if (v instanceof Long)    return (int) ((Long) v).longValue();
    return 0;
}
```

### 6.7 `BuildCompat` 补齐版本判断

```java
public static boolean isTiramisu()        { return SDK_INT >= 33 || ... }  // 13
public static boolean isUpsideDownCake()  { return SDK_INT >= 34 || ... }  // 14
public static boolean isVanillaIceCream() { return SDK_INT >= 35 || ... }  // 15
public static boolean isBaklava()         { return SDK_INT >= 36 || ... }  // 16
```

---

## 七、走过的弯路（值得记录）

调试过程中有几次判断失误，记录下来避免重犯。

### 7.1 误读 `PERMISSION_GRANTED` 的值

花了 4 轮才意识到 `PERMISSION_GRANTED = 0`、`PERMISSION_DENIED = -1`。看到日志 `-> -1` 时我一直以为是"已授权"。

**教训**：涉及返回码，先查常量定义，不要凭印象。

### 7.2 trace 打在循环之前

为了排查"为什么只注册了 3 个 hook"，我加了日志：

```java
onBindMethod();
trace("hooks=" + mMethodHookMap.size());     // ← 打在 initAnnotation 之前
for (Class<?> c : getDeclaredClasses()) initAnnotation(c);
```

打印出 `hooks=3`，我一度以为注解扫描失败。实际是**日志位置错了** —— 那 3 个是 `onBindMethod()` 手工注册的。移到循环之后立即变成 `totalHooks=47`。

### 7.3 用 Pine hook 权限入口导致死锁

尝试用 Pine hook `ContextImpl.checkSelfPermission`：

```java
Pine.ensureInitialized();   // ← 在虚拟进程内直接死锁，连异常都没有
```

Pine 的 native 初始化与虚拟环境的 Hook 冲突。这条路走不通，最终回到 binder 层，这才是正确的位置（更底层、更少依赖）。

### 7.4 日志级别导致误判

`Slog.d` 内部是 `Log.println(DEBUG, ...)`，在非 debuggable 配置下会被过滤。我因此一度以为"hook 没执行"，实际执行了但日志看不到。

最终用 `Log.e` + 写文件双重验证。生产代码里应该清理掉这些调试代码（已完成）。

---

## 八、验证

### 8.1 构建

```bash
./gradlew :app:assembleBlackBox32Debug \
          :app:assembleBlackBox64Debug \
          :app:assembleBlackBox64Release --rerun-tasks
# BUILD SUCCESSFUL
```

APK 校验：

```bash
aapt2 dump badging app-BlackBox64-debug.apk | grep -E "sdkVersion|package:"
# compileSdkVersion='36' targetSdkVersion:'36'
```

### 8.2 功能回归

| 场景 | 结果 |
|---|---|
| 宿主启动 | 正常，0 崩溃 |
| 应用安装进容器 | 正常（`install finish: 219ms`） |
| 虚拟应用启动（`com.guassianblur`） | 正常渲染，运行在 `ProxyActivity$P0` |
| QQ 启动 → 登录页 | 正常，0 崩溃 |
| QQ 录音 | 正常，4.3 秒 PCM → SILK |
| 多进程（`:raftkit`） | 正常拉起 |
| 权限判定 | 宿主已授权→`0`，未授权→`-1` |

### 8.3 未验证项

| 项目 | 原因 |
|---|---|
| Android 15 / 16 真机 | 测试机为 Android 14；15/16 的结论基于 AOSP 源码比对 |
| x86 / x86_64 | `fake_dlfcn.cpp` 无 x86_64 分支，pine x86 trampoline 上游不完整，已主动移除 |
| 32 位设备实机 | 仅验证构建产物含 `lib/armeabi-v7a/*.so` |

---

## 九、迁移清单

供后续跟进新版本时参考。

### 9.1 每次 Android 大版本都要检查

```bash
# 1. 隐藏 API 是否新增限制
# 2. libcore 层被 hook 的方法签名
curl -s "https://android.googlesource.com/platform/libcore/+/refs/heads/android<VER>-release/ojluni/src/main/java/java/io/UnixFileSystem.java?format=TEXT" | base64 -d | grep native

# 3. IActivityManager / IPackageManager 的 AIDL 变更
curl -s "https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android<VER>-release/core/java/android/app/IActivityManager.aidl?format=TEXT" | base64 -d | grep -E "startActivity|bindService|checkPermission"

# 4. ActivityThread 的内部方法是否改名
curl -s "https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android<VER>-release/core/java/android/app/ActivityThread.java?format=TEXT" | base64 -d | grep -E "getActivityClient|getLaunchingActivity|handleNewIntent"
```

### 9.2 高频变更点

| 位置 | 历史变更 |
|---|---|
| `ContextImpl.bindServiceCommon` | API 33 改用 `bindServiceInstance` |
| `ContextImpl.checkSelfPermission` | API 33 增加 `checkPermissionForDevice` |
| `ActivityThread.getLaunchingActivity` | API 33 改名 `getActivityClient` |
| `ActivityThread.handleNewIntent` | API 31 `IBinder` → `ActivityClientRecord` |
| `UnixFileSystem.canonicalize0` | API 35 增加 `boolean` 参数 |
| `IPackageManager` flags 参数 | API 33 `int` → `long` |
| `PackageParser` | API 28 拆分 `parsing/` 子包；每版本签名不同 |
| 前台服务类型 | API 34 强制 |
| receiver exported | API 34 强制 |
| `@CriticalNative` 普及 | API 29 起逐步覆盖热点方法 |

### 9.3 方法论

1. **先跑起来再修功能** —— 构建系统和运行时是两回事，分开处理；
2. **用源码确认，不要猜** —— `android.googlesource.com` 可以直接取任意版本的源码，比试错快得多；
3. **日志不可信任时用文件/e 级别** —— DEBUG 日志在很多环境被过滤；
4. **优先改"请求"而非"结果"** —— 如 `LaunchActivityItem` vs `ActivityClientRecord`，前者无时序依赖；
5. **警惕静默失败** —— `getLaunchingActivity()` 返回 null、`HookJniFun` 偏移为 0 时跳过，都不会抛异常，只会"功能不生效"。

---

## 附录：本次改动统计

```
60 files changed, 1121 insertions(+), 346 deletions(-)
```

主要文件：

| 文件 | 改动 |
|---|---|
| `build.gradle` / `gradle.properties` / `settings.gradle` | 工具链升级 |
| 8 个模块的 `build.gradle` | namespace、AGP 8 DSL |
| `BlackBoxCore.java` | 隐藏 API 豁免、权限缓存禁用 |
| `BActivityThread.java` | Application 重建、缓存处理 |
| `HCallbackProxy.java` | `LaunchActivityItem` 改写 |
| `IActivityManagerProxy.java` | `bindServiceInstance`、`checkPermissionForDevice` |
| `IPackageManagerProxy.java` | `checkPermission`、`grant/revokeRuntimePermission` |
| `BPackageManagerService.java` | 虚拟权限模型 |
| `BPackageUserState.java` | 权限状态持久化、旧格式兼容 |
| `BPackageSettings.java` | `package.conf` 带版本号、双向兼容解析 |
| `Settings.java` | 解析失败重建而非删包 |
| `PackageManagerCompat.java` | 空值不再静默传递 |
| `BProcessManagerService.java` | 复用前检查进程是否存活 |
| `ActiveServices.java` | 未安装用户态直接拒绝启动 |
| `JniHook.cpp` | `@CriticalNative`、偏移探测 |
| `UnixFileSystemHook.cpp` | `canonicalize0` 双签名 |
| `android-mirror/` | AIDL 白名单、namespace |
| 新增 3 个 | `HiddenApiCompat`、`FloatingView`、`CatLoadingView` |
