/* JNI transport only. UTF-16 input and length-prefixed UTF-8 output, never MUTF-8. */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
struct buffer { uint8_t *ptr; size_t len, capacity; };
struct context { JNIEnv *env; jobject cancel; jmethodID poll; };
extern struct buffer alldoc_doc_parse(const uint8_t *, size_t, const uint16_t *, size_t, void *, int32_t (*)(void *));
extern struct buffer alldoc_fuzzy_match(const uint8_t *, size_t, const uint16_t *, size_t, void *, int32_t (*)(void *));
extern void alldoc_doc_free(struct buffer);
static void fail(JNIEnv *e, const char *type, const char *message) {
    if ((*e)->ExceptionCheck(e)) return;
    jclass c = (*e)->FindClass(e,type);
    if (c) { (*e)->ThrowNew(e,c,message); (*e)->DeleteLocalRef(e,c); }
}
static int32_t poll(void *opaque) {
    struct context *c = opaque;
    jboolean answer = (*c->env)->CallBooleanMethod(c->env,c->cancel,c->poll);
    return (*c->env)->ExceptionCheck(c->env) ? 2 : answer ? 1 : 0;
}
static jbyteArray transport(JNIEnv *e, jclass cls, jbyteArray input, jstring name, jobject cancel, int fuzzy) {
    (void)cls;
    if (!input || !name || !cancel) { fail(e,"java/lang/NullPointerException","Null document argument"); return NULL; }
    jsize len = (*e)->GetArrayLength(e,input), nlen = (*e)->GetStringLength(e,name);
    if (len > (fuzzy ? 132*1024 : 16*1024*1024) || nlen > (fuzzy ? 128 : 512)) { fail(e,"java/lang/IllegalArgumentException","Document input exceeds bounds"); return NULL; }
    uint16_t hint[512]; (*e)->GetStringRegion(e,name,0,nlen,(jchar *)hint);
    if ((*e)->ExceptionCheck(e)) return NULL;
    jclass c = (*e)->GetObjectClass(e,cancel); if (!c) return NULL;
    jmethodID method = (*e)->GetMethodID(e,c,"isCancelled","()Z"); (*e)->DeleteLocalRef(e,c); if (!method) return NULL;
    struct context context = {e,cancel,method};
    uint8_t *data = malloc(len ? (size_t)len : 1);
    if (!data) { fail(e,"java/lang/OutOfMemoryError","Document input allocation failed"); return NULL; }
    (*e)->GetByteArrayRegion(e,input,0,len,(jbyte *)data);
    if ((*e)->ExceptionCheck(e)) { free(data); return NULL; }
    struct buffer output = (fuzzy ? alldoc_fuzzy_match : alldoc_doc_parse)(data,(size_t)len,hint,(size_t)nlen,&context,poll); free(data);
    jbyteArray answer = NULL;
    if (!(*e)->ExceptionCheck(e)) {
        if (output.len > (fuzzy ? 128 : 2*1024*1024)) fail(e,"java/lang/IllegalStateException","Document protocol limit exceeded");
        else { answer = (*e)->NewByteArray(e,(jsize)output.len); if (answer) (*e)->SetByteArrayRegion(e,answer,0,(jsize)output.len,(jbyte *)output.ptr); }
    }
    alldoc_doc_free(output); return answer;
}

jbyteArray alldoc_doc_jni(JNIEnv *e, jclass cls, jbyteArray input, jstring name, jobject cancel) {
    return transport(e,cls,input,name,cancel,0);
}
jbyteArray alldoc_fuzzy_jni(JNIEnv *e, jclass cls, jbyteArray input, jstring query, jobject cancel) {
    return transport(e,cls,input,query,cancel,1);
}
