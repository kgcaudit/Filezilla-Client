// JNI bridge to the LZMA SDK's reference 7z extractor.
//
// The 7z sources (sevenz/, lzma-sdk-license.txt) are Igor Pavlov's
// public-domain LZMA SDK. Only the reference 7z-extraction subset is
// vendored -- the files the SDK's own 7zDec sample links -- so LZMA, LZMA2,
// PPMd and the branch filters are 7-Zip's own decoder rather than a Kotlin
// port, where a mistake would be silent corruption. This lists and extracts
// only, and nothing in this app writes a 7z.
//
// The reference decoder has no AES, so an encrypted 7z lists (its header is
// in the clear) but its entries are reported as unreadable rather than
// prompting for a password no decoder here could use.
#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <string>
#include <vector>
#include <sys/stat.h>

#include "sevenz/7z.h"
#include "sevenz/7zCrc.h"
#include "sevenz/7zFile.h"

namespace {

// The one method the app cannot do: AES-256, the coder 7z uses for
// encryption. The SDK's reference decoder does not include it.
const UInt32 K_AES = 0x06F10701;

// The read buffer for the archive stream. The sample uses the same size.
const size_t kInputBufSize = (size_t)1 << 18;

// malloc/free as an ISzAlloc, so nothing here depends on the SDK's own
// Alloc.c -- which the reference 7zDec object list leaves out.
void* szAlloc(ISzAllocPtr, size_t size) { return malloc(size); }
void szFree(ISzAllocPtr, void* address) { free(address); }
const ISzAlloc g_alloc = { szAlloc, szFree };

std::string toUtf8(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// A UTF-16 name (what 7z stores) as UTF-8, joining surrogate pairs.
std::string utf16ToUtf8(const UInt16* w, size_t len) {
    std::string out;
    for (size_t i = 0; i < len; ++i) {
        unsigned int c = w[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < len) {
            unsigned int lo = w[i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                c = 0x10000 + ((c - 0xD800) << 10) + (lo - 0xDC00);
                ++i;
            }
        }
        if (c < 0x80) {
            out += (char)c;
        } else if (c < 0x800) {
            out += (char)(0xC0 | (c >> 6));
            out += (char)(0x80 | (c & 0x3F));
        } else if (c < 0x10000) {
            out += (char)(0xE0 | (c >> 12));
            out += (char)(0x80 | ((c >> 6) & 0x3F));
            out += (char)(0x80 | (c & 0x3F));
        } else {
            out += (char)(0xF0 | (c >> 18));
            out += (char)(0x80 | ((c >> 12) & 0x3F));
            out += (char)(0x80 | ((c >> 6) & 0x3F));
            out += (char)(0x80 | (c & 0x3F));
        }
    }
    return out;
}

// FILETIME 100ns ticks since 1601 -> epoch millis, or 0. Same as the rar bridge.
jlong ticksToMillis(UInt32 low, UInt32 high) {
    unsigned long long ticks = ((unsigned long long)high << 32) | low;
    if (ticks == 0) return 0;
    return (jlong)(ticks / 10000ULL - 11644473600000ULL);
}

// Everything the reference decoder can unpack. A folder using anything else
// is shown but marked out of reach, the same as an unknown method in the
// Kotlin readers.
bool isSupportedMethod(UInt32 id) {
    switch (id) {
        case 0x0:        // Copy
        case 0x3:        // Delta
        case 0x21:       // LZMA2
        case 0x30101:    // LZMA
        case 0x30401:    // PPMd
        case 0x3030103:  // BCJ x86
        case 0x3030205:  // PPC
        case 0x3030401:  // IA64
        case 0x3030501:  // ARM
        case 0x3030701:  // ARMT
        case 0x3030805:  // SPARC
        case 0x303011B:  // BCJ2
        case 0xa:        // ARM64
        case 0xb:        // RISC-V
            return true;
        default:
            return false;
    }
}

// Classifies the folder a file lives in: encrypted (AES, no decoder here) or
// using a method this build does not do. A file with no folder -- a
// directory or an empty file -- is neither.
void classify(const CSzArEx& db, UInt32 fileIndex, bool& encrypted, bool& unsupported) {
    encrypted = false;
    unsupported = false;
    UInt32 fo = db.FileToFolder[fileIndex];
    if (fo >= db.db.NumFolders) return;

    CSzData sd;
    sd.Data = db.db.CodersData + db.db.FoCodersOffsets[fo];
    sd.Size = db.db.FoCodersOffsets[fo + 1] - db.db.FoCodersOffsets[fo];
    CSzFolder folder;
    if (SzGetNextFolderItem(&folder, &sd) != SZ_OK) {
        unsupported = true;
        return;
    }
    for (UInt32 c = 0; c < folder.NumCoders; ++c) {
        UInt32 id = folder.Coders[c].MethodID;
        if (id == K_AES) encrypted = true;
        else if (!isSupportedMethod(id)) unsupported = true;
    }
}

// Makes every folder on the way to a file, like mkdir -p. Slashes only:
// names are normalised to '/' before they get here.
void makeDirs(const std::string& path) {
    for (size_t i = 1; i < path.size(); ++i) {
        if (path[i] != '/') continue;
        std::string part = path.substr(0, i);
        mkdir(part.c_str(), 0755);
    }
}

bool wantsEntry(const std::string& name, const std::vector<std::string>& picks) {
    if (picks.empty()) return true;
    for (const auto& p : picks) {
        if (name == p) return true;
        if (name.size() > p.size() && name.compare(0, p.size(), p) == 0 && name[p.size()] == '/') return true;
    }
    return false;
}

// A read stream that counts what it hands out and can stop the decode.
//
// Why this exists: SzArEx_Extract decodes a whole solid folder in one call
// before it returns a single byte, so a big 7z looked frozen -- no progress
// moved and the stop button did nothing until the decode finished, which
// for half a gigabyte is many seconds. The decoder reads its input through
// this stream as it works, so counting those reads is a live measure of how
// far the decode has got, and refusing a read is how it is stopped part way.
// It sits under the real file stream and forwards to it; the SDK is not
// touched.
struct CountingStream {
    ISeekInStream vt;      // first, so the interface pointer is this struct
    ISeekInStream* inner;  // the real file stream
    JNIEnv* env;
    jobject sink;
    jmethodID onProgress;
    jmethodID isCancelled;
    UInt64 consumed;       // compressed bytes read so far
    UInt64 totalPacked;    // compressed bytes in the file
    jlong totalUnpacked;   // uncompressed total, for the bar's scale
    UInt64 sinceReport;
    const std::string* name;
    bool active;           // off during the header read, on during extract
    bool cancelled;
};

SRes counting_Read(ISeekInStreamPtr pp, void* buf, size_t* size) {
    CountingStream* s = (CountingStream*)pp;
    SRes r = s->inner->Read(s->inner, buf, size);
    if (r != SZ_OK) return r;
    if (!s->active) return SZ_OK;
    s->consumed += *size;
    s->sinceReport += *size;
    // Every megabyte of input: report where the decode is and ask to stop.
    if (s->sinceReport >= (UInt64)(1 << 20)) {
        s->sinceReport = 0;
        // Scaled to the uncompressed total, so the bar means what the user
        // is unpacking rather than the smaller compressed size.
        jlong done = (jlong)s->consumed;
        jlong total = (jlong)s->totalPacked;
        if (s->totalUnpacked > 0 && s->totalPacked > 0) {
            done = (jlong)((double)s->consumed / (double)s->totalPacked * (double)s->totalUnpacked);
            total = s->totalUnpacked;
        }
        if (done > total) done = total;
        jstring jn = s->env->NewStringUTF(s->name ? s->name->c_str() : "");
        jvalue a[3];
        a[0].j = done;
        a[1].j = total;
        a[2].l = jn;
        s->env->CallVoidMethodA(s->sink, s->onProgress, a);
        s->env->DeleteLocalRef(jn);
        if (s->env->CallBooleanMethod(s->sink, s->isCancelled)) {
            s->cancelled = true;
            return SZ_ERROR_PROGRESS;  // stops SzArEx_Extract part way through
        }
    }
    return SZ_OK;
}

SRes counting_Seek(ISeekInStreamPtr pp, Int64* pos, ESzSeek origin) {
    CountingStream* s = (CountingStream*)pp;
    return s->inner->Seek(s->inner, pos, origin);
}

// Opens [path] as a 7z and parses its header, reading through [realStream]
// (the file's own, or the counting stream standing in front of it). The
// caller sets that stream up and, when it wraps the file, points it at
// ar->vt first. On success the caller must SzArEx_Free the db and free
// look->buf and File_Close the stream. Returns SZ_OK or an SRes.
SRes openArchive(CFileInStream* ar, CLookToRead2* look, CSzArEx* db, ISeekInStream* realStream) {
    look->buf = (Byte*)malloc(kInputBufSize);
    if (!look->buf) { File_Close(&ar->file); return SZ_ERROR_MEM; }
    LookToRead2_CreateVTable(look, False);
    look->bufSize = kInputBufSize;
    look->realStream = realStream;
    LookToRead2_INIT(look)
    CrcGenerateTable();
    SzArEx_Init(db);
    SRes res = SzArEx_Open(db, &look->vt, &g_alloc, &g_alloc);
    if (res != SZ_OK) {
        SzArEx_Free(db, &g_alloc);
        free(look->buf);
        File_Close(&ar->file);
    }
    return res;
}

// Opens the file behind [ar] and hands back its ISeekInStream, or null.
ISeekInStream* openFile(const std::string& path, CFileInStream* ar) {
    if (InFile_Open(&ar->file, path.c_str()) != 0) return nullptr;
    FileInStream_CreateVTable(ar);
    ar->wres = 0;
    return &ar->vt;
}

std::string nameOf(const CSzArEx& db, UInt32 i, std::vector<UInt16>& scratch) {
    size_t len = SzArEx_GetFileNameUtf16(&db, i, nullptr);
    if (len == 0) return {};
    scratch.resize(len);
    SzArEx_GetFileNameUtf16(&db, i, scratch.data());
    // len includes the terminating zero.
    std::string name = utf16ToUtf8(scratch.data(), len - 1);
    for (auto& c : name) if (c == '\\') c = '/';
    return name;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_org_filezilla_android_archive_SevenZipNative_nativeVersion(JNIEnv*, jclass) {
    return 1;
}

// Lists the archive by calling back sink.entry(name, size, isDir, mtime,
// encrypted, unsupported). Returns 0, or a negative SRes on a header this
// reader cannot parse (an encrypted header among them).
JNIEXPORT jint JNICALL
Java_org_filezilla_android_archive_SevenZipNative_nativeList(
        JNIEnv* env, jclass, jstring jpath, jobject sink) {
    std::string path = toUtf8(env, jpath);

    CFileInStream ar;
    CLookToRead2 look;
    CSzArEx db;
    ISeekInStream* stream = openFile(path, &ar);
    if (stream == nullptr) return -(jint)SZ_ERROR_INPUT_EOF;
    SRes res = openArchive(&ar, &look, &db, stream);
    if (res != SZ_OK) return -(jint)res;

    jclass sinkClass = env->GetObjectClass(sink);
    jmethodID entry = env->GetMethodID(sinkClass, "entry", "(Ljava/lang/String;JZJZZ)V");
    if (entry == nullptr) {
        env->ExceptionClear();
        SzArEx_Free(&db, &g_alloc);
        free(look.buf);
        File_Close(&ar.file);
        return -100;
    }

    std::vector<UInt16> scratch;
    for (UInt32 i = 0; i < db.NumFiles; ++i) {
        bool isDir = SzArEx_IsDir(&db, i);
        std::string name = nameOf(db, i, scratch);
        jlong size = isDir ? 0 : (jlong)SzArEx_GetFileSize(&db, i);
        jlong mtime = SzBitWithVals_Check(&db.MTime, i)
            ? ticksToMillis(db.MTime.Vals[i].Low, db.MTime.Vals[i].High) : 0;
        bool encrypted = false, unsupported = false;
        if (!isDir) classify(db, i, encrypted, unsupported);

        jstring jname = env->NewStringUTF(name.c_str());
        jvalue args[6];
        args[0].l = jname;
        args[1].j = size;
        args[2].z = (jboolean)isDir;
        args[3].j = mtime;
        args[4].z = (jboolean)encrypted;
        args[5].z = (jboolean)unsupported;
        env->CallVoidMethodA(sink, entry, args);
        env->DeleteLocalRef(jname);
    }

    SzArEx_Free(&db, &g_alloc);
    free(look.buf);
    File_Close(&ar.file);
    return 0;
}

// Extracts into destDir. picks null/empty = everything, else the named
// entries and what is under them. Reports and cancels through sink.
// Returns 0 ok, 1 cancelled, negative an SRes error.
JNIEXPORT jint JNICALL
Java_org_filezilla_android_archive_SevenZipNative_nativeExtract(
        JNIEnv* env, jclass, jstring jpath, jstring jdest,
        jobjectArray jpicks, jlong jtotalBytes, jboolean jskipExisting, jobject sink) {
    std::string path = toUtf8(env, jpath);
    std::string dest = toUtf8(env, jdest);

    std::vector<std::string> picks;
    if (jpicks) {
        jsize n = env->GetArrayLength(jpicks);
        for (jsize i = 0; i < n; ++i) {
            jstring s = (jstring)env->GetObjectArrayElement(jpicks, i);
            picks.push_back(toUtf8(env, s));
            env->DeleteLocalRef(s);
        }
    }

    jclass sinkClass = env->GetObjectClass(sink);
    jmethodID onProgress = env->GetMethodID(sinkClass, "progress", "(JJLjava/lang/String;)V");
    jmethodID isCancelled = env->GetMethodID(sinkClass, "cancelled", "()Z");
    if (onProgress == nullptr || isCancelled == nullptr) { env->ExceptionClear(); return -100; }

    // The compressed size, so the progress the decode's reads report can be
    // scaled to the uncompressed total the bar shows.
    UInt64 packedTotal = 0;
    {
        struct stat sb;
        if (stat(path.c_str(), &sb) == 0) packedTotal = (UInt64)sb.st_size;
    }

    CFileInStream ar;
    CLookToRead2 look;
    CSzArEx db;

    // The counting stream sits between the buffered reader and the file, so
    // the decode's reads move the bar and can be stopped. It is off while
    // the header is parsed -- those reads are not the work being watched.
    CountingStream cs;
    memset(&cs, 0, sizeof(cs));
    cs.vt.Read = counting_Read;
    cs.vt.Seek = counting_Seek;
    cs.env = env;
    cs.sink = sink;
    cs.onProgress = onProgress;
    cs.isCancelled = isCancelled;
    cs.totalPacked = packedTotal;
    cs.totalUnpacked = jtotalBytes;
    cs.active = false;
    cs.cancelled = false;

    // Open the file first so the counting stream can point at it, then open
    // the archive through the counting stream. inner must be set before the
    // header parse, which already reads through the counting stream.
    cs.inner = openFile(path, &ar);
    if (cs.inner == nullptr) return -(jint)SZ_ERROR_INPUT_EOF;
    SRes res = openArchive(&ar, &look, &db, &cs.vt);
    if (res != SZ_OK) return -(jint)res;

    mkdir(dest.c_str(), 0755);

    // The folder cache: a 7z folder holds several files together, so
    // decoding it once and keeping the buffer means the files in it are
    // copied out without decoding it again. Must start as shown.
    UInt32 blockIndex = 0xFFFFFFFF;
    Byte* outBuffer = nullptr;
    size_t outBufferSize = 0;

    jint outcome = 0;
    std::vector<UInt16> scratch;
    cs.active = true;

    for (UInt32 i = 0; i < db.NumFiles; ++i) {
        if (env->CallBooleanMethod(sink, isCancelled)) { outcome = 1; break; }

        std::string name = nameOf(db, i, scratch);
        std::string full = dest + "/" + name;
        if (SzArEx_IsDir(&db, i)) {
            makeDirs(full + "/");
            continue;
        }
        if (!wantsEntry(name, picks)) continue;
        if (jskipExisting) {
            struct stat sb;
            if (stat(full.c_str(), &sb) == 0) continue;
        }
        makeDirs(full);

        // What the progress line names while this file's folder decodes.
        cs.name = &name;
        size_t offset = 0, outSizeProcessed = 0;
        SRes r = SzArEx_Extract(&db, &look.vt, i, &blockIndex,
            &outBuffer, &outBufferSize, &offset, &outSizeProcessed, &g_alloc, &g_alloc);
        if (r != SZ_OK) {
            if (cs.cancelled) { outcome = 1; break; }
            // A folder this decoder cannot do (AES, say): skip the file and
            // report the reason at the end, the same as a bad rar entry.
            outcome = -(jint)r;
            continue;
        }

        FILE* fp = fopen(full.c_str(), "wb");
        if (!fp) { outcome = -(jint)SZ_ERROR_WRITE; continue; }
        if (outSizeProcessed > 0) {
            fwrite(outBuffer + offset, 1, outSizeProcessed, fp);
        }
        fclose(fp);
    }

    ISzAlloc_Free(&g_alloc, outBuffer);
    SzArEx_Free(&db, &g_alloc);
    free(look.buf);
    File_Close(&ar.file);
    return outcome;
}

} // extern "C"
