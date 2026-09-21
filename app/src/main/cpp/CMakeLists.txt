cmake_minimum_required(VERSION 3.22.1)
project(olorar)

# UnRAR, built from Alexander Roshal's free UnRAR source (license.txt in
# unrar/). RAR 5.0 cannot be read in Kotlin at any sane effort -- its LZ,
# PPMd and filter VM are the reference decoder's alone -- so the reference
# decoder is compiled here and driven over its own DLL C API. Only the
# files the upstream makefile's library target lists are compiled; several
# .cpp are #included by others, so globbing would double their symbols.
set(UNRAR_SOURCES
    unrar/rar.cpp unrar/strlist.cpp unrar/strfn.cpp unrar/pathfn.cpp
    unrar/smallfn.cpp unrar/global.cpp unrar/file.cpp unrar/filefn.cpp
    unrar/filcreat.cpp unrar/archive.cpp unrar/arcread.cpp unrar/unicode.cpp
    unrar/system.cpp unrar/crypt.cpp unrar/crc.cpp unrar/rawread.cpp
    unrar/encname.cpp unrar/resource.cpp unrar/match.cpp unrar/timefn.cpp
    unrar/rdwrfn.cpp unrar/consio.cpp unrar/options.cpp unrar/errhnd.cpp
    unrar/rarvm.cpp unrar/secpassword.cpp unrar/rijndael.cpp unrar/getbits.cpp
    unrar/sha1.cpp unrar/sha256.cpp unrar/blake2s.cpp unrar/hash.cpp
    unrar/extinfo.cpp unrar/extract.cpp unrar/volume.cpp unrar/list.cpp
    unrar/find.cpp unrar/unpack.cpp unrar/headers.cpp unrar/threadpool.cpp
    unrar/rs16.cpp unrar/cmddata.cpp unrar/ui.cpp unrar/largepage.cpp
    unrar/filestr.cpp unrar/scantree.cpp unrar/dll.cpp unrar/qopen.cpp
)

add_library(olorar SHARED rar_jni.cpp ${UNRAR_SOURCES})

target_compile_definitions(olorar PRIVATE
    RARDLL _FILE_OFFSET_BITS=64 _LARGEFILE_SOURCE
)

# The upstream code is warning-heavy by modern clang and builds with these
# off in its own makefile; treat it as the third-party code it is.
target_compile_options(olorar PRIVATE
    -O2 -fexceptions -w
)

set_target_properties(olorar PROPERTIES CXX_STANDARD 14 CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)
target_link_libraries(olorar ${log-lib})
