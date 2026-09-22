# What R8 must not remove or rename, and why.
#
# Very little, because the app reaches nothing by name: no Class.forName,
# no resource lookups by identifier, no serialization library. Room and
# Compose ship their own rules with the libraries, so they are not
# repeated here. Every rule below is either a fact R8 cannot see or a
# failure mode severe enough to insure against cheaply.

# The enums are written into the database as names and read back with
# valueOf. R8 understands valueOf and keeps what it needs, so this is
# insurance rather than a fix -- but the thing it insures is the user's
# saved servers and their queued transfers becoming unreadable after an
# update, which is not a failure worth being clever about.
-keepclassmembers enum org.filezilla.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Room's generated implementation is found by name at runtime, by Room
# itself rather than by us. Its own rules cover this; named here because
# a database that cannot be opened takes the passwords and the resume
# offsets with it.
-keep class org.filezilla.android.data.AppDatabase_Impl { <init>(); }

# Stack traces from a sideloaded build are read by a person, not uploaded
# to a service that could map them back. Line numbers are a few kilobytes.
-keepattributes SourceFile,LineNumberTable

# The engine throws these across the module boundary and the app matches on
# type. R8 would not remove a type that is caught, but it may rename it --
# and the name is what ends up in a log the user sends.
-keep class org.filezilla.ftp.net.CertificateNotTrusted { *; }
-keep class org.filezilla.ftp.net.ServerCertificate { *; }

# When a failure carries no message of its own, the app shows the
# exception's class name instead -- it is the only thing left that says
# what went wrong, and it ends up in the log a user sends. Renamed, it
# says "a.b.c". Names only: these still get shrunk away if nothing throws
# them, and the platform's own exceptions were never renamed anyway.
-keepnames class * extends java.lang.Throwable

# The RAR bridge is called across JNI by name: the native library resolves
# Java_..._RarNative_nativeList and friends by their exact names, and the
# native code looks up Sink's methods with GetMethodID. R8 renaming any of
# them turns into an UnsatisfiedLinkError or a missing-method crash at run
# time, only in release. So the bridge and its callback interface are kept
# whole.
-keep class org.filezilla.android.archive.RarNative { *; }
-keep interface org.filezilla.android.archive.RarNative$Sink { *; }
-keepclassmembers class * implements org.filezilla.android.archive.RarNative$Sink { *; }

# The 7z bridge is the same: native code resolves Java_..._SevenZipNative_*
# by name and looks up Sink's methods with GetMethodID, so R8 must leave the
# bridge and its callback interface unrenamed.
-keep class org.filezilla.android.archive.SevenZipNative { *; }
-keep interface org.filezilla.android.archive.SevenZipNative$Sink { *; }
-keepclassmembers class * implements org.filezilla.android.archive.SevenZipNative$Sink { *; }
