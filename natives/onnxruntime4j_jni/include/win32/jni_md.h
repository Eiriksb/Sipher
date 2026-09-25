/* Machine-dependent JNI types for Windows, equivalent to the JDK's include/win32/jni_md.h. Lets the Windows glue be
 * cross-compiled on any host without a Windows JDK. */
#ifndef _JAVASOFT_JNI_MD_H_
#define _JAVASOFT_JNI_MD_H_

#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)
#define JNICALL __stdcall

typedef long jint;
typedef __int64 jlong;
typedef signed char jbyte;

#endif
