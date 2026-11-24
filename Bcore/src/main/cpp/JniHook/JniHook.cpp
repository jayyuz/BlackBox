//
// Created by Milk on 3/8/21.
//

#include <jni.h>
#include "JniHook.h"
#include "Log.h"
#include "ArtMethod.h"

static struct {
    int api_level;
    unsigned int art_field_size;
    int art_field_flags_offset;

    unsigned int art_method_size;
    int art_method_flags_offset;
    int art_method_native_offset;

    int class_flags_offset;

    /*
     * Set when the ArtMethod layout could not be determined. Both offsets are
     * zero-initialised, and offset 0 is NOT a usable address inside an ArtMethod:
     * it holds the declaring_class pointer on most releases. Writing flags there
     * corrupts the class reference, and ART then dies later inside
     * art::FindMethod during RegisterNatives with a SIGSEGV on a garbage address.
     * Hooking must therefore be skipped entirely rather than attempted at offset 0.
     */
    bool disabled;

    jclass method_utils_class;
    jmethodID get_method_desc_id;
    jmethodID get_method_declaring_class_id;
    jmethodID get_method_name_id;

} HookEnv;

static const char *GetMethodDesc(JNIEnv *env, jobject javaMethod) {
    auto desc = reinterpret_cast<jstring>(env->CallStaticObjectMethod(HookEnv.method_utils_class,
                                                                      HookEnv.get_method_desc_id,
                                                                      javaMethod));
    return env->GetStringUTFChars(desc, JNI_FALSE);
}

static const char *GetMethodDeclaringClass(JNIEnv *env, jobject javaMethod) {
    auto desc = reinterpret_cast<jstring>(env->CallStaticObjectMethod(HookEnv.method_utils_class,
                                                                      HookEnv.get_method_declaring_class_id,
                                                                      javaMethod));
    return env->GetStringUTFChars(desc, JNI_FALSE);
}

static const char *GetMethodName(JNIEnv *env, jobject javaMethod) {
    auto desc = reinterpret_cast<jstring>(env->CallStaticObjectMethod(HookEnv.method_utils_class,
                                                                      HookEnv.get_method_name_id,
                                                                      javaMethod));
    return env->GetStringUTFChars(desc, JNI_FALSE);
}

inline static uint32_t GetAccessFlags(const char *art_method) {
    return *reinterpret_cast<const uint32_t *>(art_method + HookEnv.art_method_flags_offset);
}

inline static bool SetAccessFlags(char *art_method, uint32_t flags) {
    *reinterpret_cast<uint32_t *>(art_method + HookEnv.art_method_flags_offset) = flags;
    return true;
}

inline static bool AddAccessFlag(char *art_method, uint32_t flag) {
    uint32_t old_flag = GetAccessFlags(art_method);
    uint32_t new_flag = old_flag | flag;
    return new_flag != old_flag && SetAccessFlags(art_method, new_flag);
}

inline static bool ClearAccessFlag(char *art_method, uint32_t flag) {
    uint32_t old_flag = GetAccessFlags(art_method);
    uint32_t new_flag = old_flag & ~flag;
    return new_flag != old_flag && SetAccessFlags(art_method, new_flag);
}

inline static bool HasAccessFlag(char *art_method, uint32_t flag) {
    uint32_t flags = GetAccessFlags(art_method);
    return (flags & flag) == flag;
}

inline static bool ClearFastNativeFlag(char *art_method) {
    // FastNative
    return HookEnv.api_level < __ANDROID_API_P__ && ClearAccessFlag(art_method, kAccFastNative);
}

/*
 * @CriticalNative changes the ABI of the callee: ART generates a stub that
 * passes the declared arguments straight through, without a JNIEnv* or jclass.
 * All replacement implementations in this library are ordinary JNI functions,
 * so the flag must be dropped before RegisterNatives or the hook will be
 * invoked with a garbage environment pointer.
 *
 * Binder.getCallingUid() carries @CriticalNative from Android 10 (API 29)
 * onwards, which is exactly one of the methods hooked below, so this is not
 * merely defensive.
 */
inline static void ClearCriticalNativeFlag(char *art_method) {
    ClearAccessFlag(art_method, kAccCriticalNative);
}

static void *GetArtMethod(JNIEnv *env, jclass clazz, jmethodID methodId) {
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        jclass executable = env->FindClass("java/lang/reflect/Executable");
        jfieldID artId = env->GetFieldID(executable, "artMethod", "J");
        jobject method = env->ToReflectedMethod(clazz, methodId, true);
        return reinterpret_cast<void *>(env->GetLongField(method, artId));
    } else {
        return methodId;
    }
}

static void *GetFieldMethod(JNIEnv *env, jobject field) {
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        jclass fieldClass = env->FindClass("java/lang/reflect/Field");
        jmethodID getArtField = env->GetMethodID(fieldClass, "getArtField", "()J");
        return reinterpret_cast<void *>(env->CallLongMethod(field, getArtField));
    } else {
        return env->FromReflectedField(field);
    }
}

bool CheckFlags(void *artMethod) {
    char *method = static_cast<char *>(artMethod);
    if (!HasAccessFlag(method, kAccNative)) {
        ALOGE("not native method");
        return false;
    }
    ClearFastNativeFlag(method);
    // Must happen for every API level: our replacement functions use the plain
    // JNI ABI while @CriticalNative methods are called without JNIEnv*/jclass.
    ClearCriticalNativeFlag(method);
    return true;
}

void JniHook::HookJniFun(JNIEnv *env, jobject java_method, void *new_fun,
                         void **orig_fun, bool is_static) {
    const char *class_name = GetMethodDeclaringClass(env, java_method);
    const char *method_name = GetMethodName(env, java_method);
    const char *sign = GetMethodDesc(env, java_method);
    HookJniFun(env, class_name, method_name, sign, new_fun, orig_fun, is_static);
}

void
JniHook::HookJniFun(JNIEnv *env, const char *class_name, const char *method_name, const char *sign,
                    void *new_fun, void **orig_fun, bool is_static) {
    if (HookEnv.art_method_native_offset == 0 || HookEnv.art_method_flags_offset == 0
        || HookEnv.disabled) {
        return;
    }
    jclass clazz = env->FindClass(class_name);
    if (!clazz) {
        ALOGD("findClass fail: %s %s", class_name, method_name);
        env->ExceptionClear();
        return;
    }
    // Clear any exception before the next JNI lookup. A leftover pending exception
    // makes the following FindClass abort the whole process with
    // "JNI DETECTED ERROR IN APPLICATION: JNI FindClass called with pending
    // exception", which is how a missing overload used to kill every guest:
    // UnixFileSystem.canonicalize0(String) is no longer native on Android 16, so
    // GetMethodID below succeeds but CheckFlags rejects it, and the exception from
    // the preceding lookup stayed armed until BaseHook's next FindClass.
    env->ExceptionClear();

    jmethodID method = nullptr;
    if (is_static) {
        method = env->GetStaticMethodID(clazz, method_name, sign);
    } else {
        method = env->GetMethodID(clazz, method_name, sign);
    }
    if (!method) {
        env->ExceptionClear();
        ALOGD("get method id fail: %s %s %s", class_name, method_name, sign);
        return;
    }
    env->ExceptionClear();

    JNINativeMethod gMethods[] = {
            {method_name, sign, (void *) new_fun},
    };

    auto artMethod = reinterpret_cast<uintptr_t *>(GetArtMethod(env, clazz, method));
    env->ExceptionClear();
    if (!CheckFlags(artMethod)) {
        // Expected on most releases: the signature exists but is no longer a
        // native method, or this ROM never had it. Not an error.
        ALOGD("skip (not a native method): %s %s", class_name, method_name);
        return;
    }
    *orig_fun = reinterpret_cast<void *>(artMethod[HookEnv.art_method_native_offset]);
    if (env->RegisterNatives(clazz, gMethods, 1) < 0) {
        ALOGE("jni hook error. class：%s, method：%s", class_name, method_name);
        env->ExceptionClear();
        return;
    }
    env->ExceptionClear();
    // FastNative
    if (HookEnv.api_level == __ANDROID_API_O__ || HookEnv.api_level == __ANDROID_API_O_MR1__) {
        AddAccessFlag((char *) artMethod, kAccFastNative);
    }
    ALOGD("register class：%s, method：%s success!", class_name, method_name);
}

__attribute__((section (".mytext")))  JNICALL void native_offset
        (JNIEnv *env, jclass obj) {
}

__attribute__((section (".mytext")))  JNICALL void native_offset2
        (JNIEnv *env, jclass obj) {
}

__attribute__((section (".mytext")))  JNICALL void set_method_accessible
        (JNIEnv *env, jclass obj, jclass clazz, jobject method) {
    jmethodID methodId = env->FromReflectedMethod(method);
    char *art_method = static_cast<char *>(GetArtMethod(env, clazz, methodId));
    AddAccessFlag(art_method, kAccPublic);
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        AddAccessFlag(art_method, kAccPublicApi);
    }
}

__attribute__((section (".mytext")))  JNICALL void set_field_accessible
        (JNIEnv *env, jclass obj, jclass clazz, jobject field) {
    char *artField = static_cast<char *>(GetFieldMethod(env, field));
    AddAccessFlag(artField, kAccPublic);
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        AddAccessFlag(artField, kAccPublicApi);
    }
    ClearAccessFlag(artField, kAccFinal);
}

void registerNative(JNIEnv *env) {
    jclass clazz = env->FindClass("top/niunaijun/jnihook/jni/JniHook");
    JNINativeMethod gMethods[] = {
            {"nativeOffset",  "()V",                                            (void *) native_offset},
            {"nativeOffset2", "()V",                                            (void *) native_offset2},
            {"setAccessible", "(Ljava/lang/Class;Ljava/lang/reflect/Method;)V", (void *) set_method_accessible},
            {"setAccessible", "(Ljava/lang/Class;Ljava/lang/reflect/Field;)V",  (void *) set_field_accessible},
    };
    if (env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0])) < 0) {
        ALOGE("jni register error.");
    }
}

void JniHook::InitJniHook(JNIEnv *env, int api_level) {
    registerNative(env);
    HookEnv.api_level = api_level;

    jclass clazz = env->FindClass("top/niunaijun/jnihook/jni/JniHook");
    jmethodID nativeOffsetId = env->GetStaticMethodID(clazz, "nativeOffset", "()V");
    jmethodID nativeOffset2Id = env->GetStaticMethodID(clazz, "nativeOffset2", "()V");

    jfieldID nativeOffsetFieldId = env->GetStaticFieldID(clazz, "NATIVE_OFFSET", "I");
    jfieldID nativeOffsetField2Id = env->GetStaticFieldID(clazz, "NATIVE_OFFSET_2", "I");

    void *nativeOffsetField = GetFieldMethod(env, env->ToReflectedField(clazz, nativeOffsetFieldId,
                                                                        true));
    void *nativeOffsetField2 = GetFieldMethod(env, env->ToReflectedField(clazz, nativeOffsetField2Id,
                                                                         true));
    HookEnv.art_field_size = (size_t) nativeOffsetField2 - (size_t) nativeOffsetField;

    void *nativeOffset = GetArtMethod(env, clazz, nativeOffsetId);
    void *nativeOffset2 = GetArtMethod(env, clazz, nativeOffset2Id);
    // Byte distance between two consecutive ArtMethod instances. These methods
    // are adjacent in the dex (declared next to each other), so the delta also
    // equals the real sizeof(ArtMethod), which we do not know statically.
    HookEnv.art_method_size = (size_t) ((char *) nativeOffset2 - (char *) nativeOffset);
    if (HookEnv.art_method_size == 0 || HookEnv.art_method_size > 512) {
        // Not adjacent (inlined, ordered differently, or the reflective artMethod
        // accessor returned something unexpected). Sizing the scan from a bogus
        // value would read past the ArtMethod, so give up on hooking entirely
        // instead of guessing.
        ALOGE("JniHook: implausible ArtMethod size %zu, disabling JNI hooks",
              HookEnv.art_method_size);
        HookEnv.art_method_native_offset = 0;
        HookEnv.art_method_flags_offset = 0;
        HookEnv.disabled = true;
        return;
    }
    const size_t slots = HookEnv.art_method_size / sizeof(uintptr_t);

    // calc native offset
    auto artMethod = reinterpret_cast<uintptr_t *>(nativeOffset);
    for (size_t i = 0; i < slots; ++i) {
        if (reinterpret_cast<void *>(artMethod[i]) == native_offset) {
            HookEnv.art_method_native_offset = (int) i;
            break;
        }
    }
    if (HookEnv.art_method_native_offset == 0) {
        // Without this, HookJniFun silently does nothing for the whole process.
        ALOGE("JniHook: unable to locate ArtMethod::data_ (native offset)");
    }

    // Locate the access-flags field inside ArtMethod.
    //
    // The expected value is derived from this method's own flags, but the exact
    // bit pattern differs across releases: Android 16 added kAccNterpEntryPointFastPath
    // and reordered hidden-API bits, and the public-API bit is not always set on a
    // freshly linked native method. Rather than guess one combination, scan for
    // every plausible one and take the first hit.
    const uint32_t kRequired = kAccPublic | kAccStatic | kAccNative;
    const uint32_t kOptional[] = {kAccFinal, kAccPublicApi, kAccCorePlatformApi};
    char *start = reinterpret_cast<char *>(artMethod);
    for (size_t i = 1; i < HookEnv.art_method_size / sizeof(uint32_t); ++i) {
        auto value = *(uint32_t *) (start + i * sizeof(uint32_t));
        if ((value & kRequired) != kRequired) {
            continue;
        }
        // Reject slots holding unrelated bits; a real access-flags word is
        // composed only of the well-known flag bits.
        const uint32_t kKnown = kAccPublic | kAccPrivate | kAccProtected | kAccStatic
                                | kAccFinal | kAccSynchronized | kAccBridge | kAccVarargs
                                | kAccNative | kAccAbstract | kAccStrict | kAccSynthetic
                                | kAccPublicApi | kAccCorePlatformApi | kAccFastNative
                                | kAccCriticalNative;
        if ((value & ~kKnown) != 0) {
            continue;
        }
        HookEnv.art_method_flags_offset = (int) (i * sizeof(uint32_t));
        ALOGD("JniHook: flags slot at %d (0x%08x)", HookEnv.art_method_flags_offset, value);
        break;
    }
    if (HookEnv.art_method_flags_offset == 0 || HookEnv.art_method_native_offset == 0) {
        // Do NOT fall through: offset 0 is the declaring_class pointer, so writing
        // flags there corrupts the class and ART crashes inside FindMethod the next
        // time RegisterNatives runs (observed as SIGSEGV on Android 16).
        //
        // Note: keep going to the MethodUtils setup below. HookJniFun checks
        // `disabled` itself, so nothing gets hooked, but every other user of this
        // JniHook instance still gets a fully initialised environment - bailing out
        // here left the :black server process unable to start at all.
        ALOGE("JniHook: ArtMethod layout unknown (native=%d flags=%d), disabling JNI hooks",
              HookEnv.art_method_native_offset, HookEnv.art_method_flags_offset);
        HookEnv.disabled = true;
        HookEnv.art_method_native_offset = 0;
        HookEnv.art_method_flags_offset = 0;
    }

    uint32_t flags = 0x0;
    flags = flags | kAccPublic;
    flags = flags | kAccStatic;
    flags = flags | kAccFinal;
    if (api_level >= __ANDROID_API_Q__) {
        flags = flags | kAccPublicApi;
    }
    char *fieldStart = reinterpret_cast<char *>(nativeOffsetField);
    for (size_t i = 1; i < HookEnv.art_field_size / sizeof(uint32_t); ++i) {
        auto value = *(int32_t *) (fieldStart + i * sizeof(int32_t));
        if (value == flags) {
            HookEnv.art_field_flags_offset = (int) (i * sizeof(int32_t));
            break;
        }
    }

    HookEnv.method_utils_class = env->FindClass("top/niunaijun/jnihook/MethodUtils");
    ALOGD("JniHook: layout native=%d flags=%d art_method_size=%u api=%d",
          HookEnv.art_method_native_offset, HookEnv.art_method_flags_offset,
          HookEnv.art_method_size, api_level);
    HookEnv.get_method_desc_id = env->GetStaticMethodID(HookEnv.method_utils_class, "getDesc",
                                                        "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
    HookEnv.get_method_declaring_class_id = env->GetStaticMethodID(HookEnv.method_utils_class,
                                                                   "getDeclaringClass",
                                                                   "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
    HookEnv.get_method_name_id = env->GetStaticMethodID(HookEnv.method_utils_class, "getMethodName",
                                                        "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
}

