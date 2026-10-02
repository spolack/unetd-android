# The native layer: libubox (subset) + json-c + unetd + unet-dht (as a library
# on unetd's loop) + the core wrapper, in one shared library; plus libwg-go,
# cross-compiled by Go.
#
# Two consumers:
#   - AGP via externalNativeBuild (ANDROID is set by the NDK toolchain file):
#     builds libunet-android.so with the JNI bindings, and libwg-go.so.
#   - the host tests (tests/host): the same sources without JNI, linked into a
#     static library and a test program, so the core can be exercised on Linux
#     before it ever runs on a device.
cmake_minimum_required(VERSION 3.22)
project(unetd-android C)

set(ROOT ${CMAKE_CURRENT_SOURCE_DIR}/..)
set(THIRD_PARTY ${ROOT}/third_party)
set(UNETD ${THIRD_PARTY}/unetd)
set(UBOX ${THIRD_PARTY}/libubox)

option(UNETD_BUILD_LIBWG_GO "Cross-compile libwg-go as part of the build" ${ANDROID})
set(GO_EXECUTABLE "go" CACHE STRING "Go toolchain used for libwg-go")

# ---- json-c (static) -------------------------------------------------------
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC_LIBS ON CACHE BOOL "" FORCE)
set(BUILD_APPS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(DISABLE_WERROR ON CACHE BOOL "" FORCE)
set(DISABLE_EXTRA_LIBS ON CACHE BOOL "" FORCE)
add_subdirectory(${THIRD_PARTY}/json-c json-c EXCLUDE_FROM_ALL)

# ---- libubox, only what unetd uses ----------------------------------------
# The sources include <libubox/...>, so stage the headers under that name.
file(GLOB UBOX_HEADERS ${UBOX}/*.h)
file(COPY ${UBOX_HEADERS} DESTINATION ${CMAKE_BINARY_DIR}/include/libubox)

add_library(ubox STATIC
	${UBOX}/avl.c ${UBOX}/avl-cmp.c ${UBOX}/blob.c ${UBOX}/blobmsg.c
	${UBOX}/blobmsg_json.c ${UBOX}/uloop.c ${UBOX}/usock.c ${UBOX}/utils.c
	${UBOX}/vlist.c ${UBOX}/safe_list.c ${UBOX}/base64.c
)
target_include_directories(ubox PUBLIC ${CMAKE_BINARY_DIR}/include)
target_include_directories(ubox PRIVATE ${THIRD_PARTY}/json-c ${CMAKE_BINARY_DIR}/json-c)
target_compile_options(ubox PRIVATE -std=gnu99 -D_GNU_SOURCE -Wall -Wno-unused-parameter)
target_link_libraries(ubox PUBLIC json-c)

# ---- unetd: the Android configuration --------------------------------------
# No ubus, no VXLAN, no kernel WireGuard backend: wg-user.c (UAPI over a unix
# socket) is the only backend compiled in. main.c is replaced by core/.
set(UNETD_COMMON_FLAGS -std=gnu99 -Wall -Wmissing-declarations
	-Wno-unused-parameter -DRUNSTATEDIR="/var/run")

add_library(unet STATIC
	${UNETD}/curve25519.c ${UNETD}/siphash.c ${UNETD}/sha512.c ${UNETD}/fprime.c
	${UNETD}/f25519.c ${UNETD}/ed25519.c ${UNETD}/edsign.c ${UNETD}/auth-data.c
	${UNETD}/chacha20.c ${UNETD}/pex-msg.c ${UNETD}/utils.c ${UNETD}/stun.c
)
target_include_directories(unet PUBLIC ${UNETD})
target_compile_options(unet PRIVATE ${UNETD_COMMON_FLAGS})
target_link_libraries(unet PUBLIC ubox)

# unet-dht as a library (patch 0013): the core runs it on unetd's own uloop.
add_library(udht STATIC ${UNETD}/dht.c ${UNETD}/udht.c)
target_compile_options(udht PRIVATE ${UNETD_COMMON_FLAGS} -DUDHT_LIBRARY)
target_link_libraries(udht PUBLIC unet)

add_library(unetd-core STATIC
	${UNETD}/network.c ${UNETD}/host.c ${UNETD}/service.c
	${UNETD}/pex.c ${UNETD}/pex-stun.c ${UNETD}/wg.c ${UNETD}/wg-user.c
	core/unetd_core.c core/unetd_log.c
)
target_include_directories(unetd-core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/core)
target_compile_options(unetd-core PRIVATE ${UNETD_COMMON_FLAGS})
target_link_libraries(unetd-core PUBLIC unet udht)

# ---- the Android library ---------------------------------------------------
if(ANDROID)
	add_library(unet-android SHARED jni/unetd_jni.c)
	target_compile_options(unet-android PRIVATE -Wall -fvisibility=hidden)
	target_link_libraries(unet-android PRIVATE unetd-core log)
	# Only the JNI entry points are exported.
	target_link_options(unet-android PRIVATE -Wl,--exclude-libs,ALL)
endif()

# ---- libwg-go --------------------------------------------------------------
if(UNETD_BUILD_LIBWG_GO)
	set(LIBWG_GO ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libwg-go.so)
	# wireguard-go itself is a Go module dependency (libwg-go/go.mod), fetched
	# by Go; only the glue here is a build input.
	file(GLOB WG_GO_SOURCES CONFIGURE_DEPENDS
		${CMAKE_CURRENT_SOURCE_DIR}/libwg-go/*.go
		${CMAKE_CURRENT_SOURCE_DIR}/libwg-go/*.c
	)
	add_custom_command(
		OUTPUT ${LIBWG_GO}
		COMMAND ${ROOT}/scripts/build-libwg-go.sh
			--abi ${ANDROID_ABI}
			--cc ${CMAKE_C_COMPILER}
			--target ${CMAKE_C_COMPILER_TARGET}
			--sysroot ${CMAKE_SYSROOT}
			--cflags "${CMAKE_C_FLAGS}"
			--ldflags "${CMAKE_SHARED_LINKER_FLAGS}"
			--go ${GO_EXECUTABLE}
			--out ${LIBWG_GO}
		DEPENDS ${WG_GO_SOURCES}
			${CMAKE_CURRENT_SOURCE_DIR}/libwg-go/go.mod
			${CMAKE_CURRENT_SOURCE_DIR}/libwg-go/go.sum
			${ROOT}/scripts/build-libwg-go.sh
		COMMENT "Cross-compiling libwg-go for ${ANDROID_ABI}"
		VERBATIM
	)
	add_custom_target(libwg-go ALL DEPENDS ${LIBWG_GO})
endif()

# ---- host test program -----------------------------------------------------
if(NOT ANDROID)
	add_executable(core-test ${ROOT}/tests/host/core-test.c)
	target_link_libraries(core-test PRIVATE unetd-core pthread)
	target_compile_options(core-test PRIVATE -Wall)
endif()
