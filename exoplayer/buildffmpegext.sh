#!/usr/bin/bash

set -euo pipefail

if [ -z "${1:-}" ]; then

	echo "Action was not provided and is required build.sh [exoplayer|ffmpeg|ndk|buildffmpeg|buildexoplayer|deploy|all]"
    exit

fi

FFmpegExtVersion="${FFMPEG_EXT_VERSION:-2.19.1}"
ExoPlayerVersion="${EXOPLAYER_VERSION:-r${FFmpegExtVersion}}"
FFmpegVersion="${FFMPEG_VERSION:-release/4.2}"
NDKTag="${NDK_TAG:-r25c}"
NDKVersion="${NDK_VERSION:-25.2.9519653}"
AndroidApiLevel="${ANDROID_API_LEVEL:-21}"

#I think we should check these and maybe
#export ANDROID_SDK_ROOT=/home/jvl711/Documents/sdk/
#export ANDROID_HOME=/home/jvl711/Documents/sdk/

ENABLED_DECODERS=(vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd)

ROOT_PATH="$(pwd)"
BUILD_PATH="${EXOPLAYER_BUILD_PATH:-${ROOT_PATH}/build}"
HOST_PLATFORM="linux-x86_64"
EXOPLAYER_ROOT="${BUILD_PATH}/ExoPlayer"
NDK_PATH="${BUILD_PATH}/android-ndk-${NDKTag}"
export ANDROID_NDK_HOME="${NDK_PATH}"
FFMPEG_PATH="${BUILD_PATH}/FFmpeg"
FFMPEG_EXT_PATH="${EXOPLAYER_ROOT}/extensions/ffmpeg/src/main"
FFMPEG_EXT_OUTPUT_PATH="${EXOPLAYER_ROOT}/extensions/ffmpeg/buildout/outputs/aar"
NDK_BIN_PATH="${NDK_PATH}/toolchains/llvm/prebuilt/${HOST_PLATFORM}/bin"
FFMPEG_CMAKE_FILE="${FFMPEG_EXT_PATH}/jni/CMakeLists.txt"

# 16KB page-size compatible linker settings for Android 15+ devices.
export LDFLAGS="-Wl,-z,max-page-size=16384 ${LDFLAGS:-}"

echo "ROOT_PATH: $ROOT_PATH" 
echo "BUILD_PATH: $BUILD_PATH"  
echo "EXOPLAYER_ROOT: $EXOPLAYER_ROOT"
echo "NDK_PATH: $NDK_PATH"
echo "NDKVersion: $NDKVersion"
echo "AndroidApiLevel: $AndroidApiLevel"
echo "FFMPEG_PATH: $FFMPEG_PATH"
echo "FFMPEG_EXT_PATH: $FFMPEG_EXT_PATH"
echo "HOST_PLATFORM: $HOST_PLATFORM"
echo "FFmpegVersion: $FFmpegVersion"
echo "FFmpegExtVersion: $FFmpegExtVersion"


if [ ! -d $BUILD_PATH ]; then

	mkdir $BUILD_PATH

fi



#--------------------------------------------------- ExoPlayer Checkout ---------------------------------------------------#

if [ $1 = "exoplayer" ] || [ $1 = "all" ]; then

	cd 	$BUILD_PATH

	echo "Setting up ExoPlayer source code for version: $ExoPlayerVersion"

	if [ -d $EXOPLAYER_ROOT  ]; then
		echo "ExoPlayer already exist..."
		cd $EXOPLAYER_ROOT
		if git symbolic-ref -q HEAD >/dev/null; then
			git pull
			git reset --hard
		fi
		git checkout $ExoPlayerVersion
	else
		echo "ExoPlayer does not exist. Cloning library from GitHub"
		git clone https://github.com/google/ExoPlayer.git
		cd $EXOPLAYER_ROOT
		git checkout $ExoPlayerVersion
	fi

	cd 	$BUILD_PATH

fi

#--------------------------------------------------- FFMpeg Checkout ---------------------------------------------------#

if [ $1 = "ffmpeg" ] || [ $1 = "all" ]; then

	cd 	$BUILD_PATH

	if [ -d $FFMPEG_PATH ]; then
		echo "FFmpeg already exist..."
		cd $FFMPEG_PATH 
		git pull
		git reset --hard
		git checkout $FFmpegVersion
	else
		echo "FFmpeg does not exist. Cloning library from GitHub"
		git clone https://github.com/FFmpeg/FFmpeg.git
		cd $FFMPEG_PATH
		git checkout $FFmpegVersion

	fi	

	cd 	$BUILD_PATH

	echo "Adding symbolic link to FFmpeg source code in ExoPlayer project"
	cd "${FFMPEG_EXT_PATH}/jni"
	if [ -e ffmpeg ] || [ -L ffmpeg ]; then
		rm -rf ffmpeg
	fi
	ln -s "$FFMPEG_PATH" ffmpeg

	cd 	$BUILD_PATH

fi


#------------------------------------------------------ NDK Download ------------------------------------------------------#

if [ $1 = "ndk" ] || [ $1 = "all" ]; then

	cd 	$BUILD_PATH

	echo "Setting up NDK library and downloading and unzipping if necessary"

	if [ -d $ANDROID_NDK_HOME ]; then
		echo "NDK exists"
	else
		echo "Downloading and unzippiong NDK"
		wget "https://dl.google.com/android/repository/android-ndk-${NDKTag}-linux.zip"
		unzip "android-ndk-${NDKTag}-linux.zip"
	fi

	# On NTFS mounts, symlinks in the NDK zip may be materialized as plain
	# text files (for example clang -> clang-14). Replace with launchers that
	# set LD_LIBRARY_PATH so clang-14 can find libc++.so.1.
	if [ -f "${NDK_BIN_PATH}/clang" ] && [ ! -L "${NDK_BIN_PATH}/clang" ]; then
		if [ "$(tr -d '\r\n' < "${NDK_BIN_PATH}/clang")" = "clang-14" ]; then
			cat > "${NDK_BIN_PATH}/clang" <<'EOF'
#!/bin/sh
BIN_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
export LD_LIBRARY_PATH="$BIN_DIR/../lib64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec "$BIN_DIR/clang-14" "$@"
EOF
			chmod +x "${NDK_BIN_PATH}/clang"
		fi
	fi
	if [ -f "${NDK_BIN_PATH}/clang++" ] && [ ! -L "${NDK_BIN_PATH}/clang++" ]; then
		if [ "$(tr -d '\r\n' < "${NDK_BIN_PATH}/clang++")" = "clang" ]; then
			cat > "${NDK_BIN_PATH}/clang++" <<'EOF'
#!/bin/sh
BIN_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
export LD_LIBRARY_PATH="$BIN_DIR/../lib64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec "$BIN_DIR/clang" "$@"
EOF
			chmod +x "${NDK_BIN_PATH}/clang++"
		fi
	fi

	if [ -f "${NDK_BIN_PATH}/clang-14" ]; then
		rm -f "${NDK_BIN_PATH}/clang" "${NDK_BIN_PATH}/clang++"
		cat > "${NDK_BIN_PATH}/clang" <<'EOF'
#!/bin/sh
BIN_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
export LD_LIBRARY_PATH="$BIN_DIR/../lib64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec "$BIN_DIR/clang-14" "$@"
EOF
		chmod +x "${NDK_BIN_PATH}/clang"
		cat > "${NDK_BIN_PATH}/clang++" <<'EOF'
#!/bin/sh
BIN_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
export LD_LIBRARY_PATH="$BIN_DIR/../lib64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec "$BIN_DIR/clang" "$@"
EOF
		chmod +x "${NDK_BIN_PATH}/clang++"
	fi

	cd 	$BUILD_PATH

fi

#------------------------------------------------------ Build FFmpeg ------------------------------------------------------#

if [ $1 = "buildffmpeg" ] || [ $1 = "all" ]; then

	cd 	$BUILD_PATH

	echo "Building FFmpeg..."

	# Exo's script defaults some ABIs to API16, but modern NDKs (r25+) no longer
	# ship those compiler wrappers. Align to app minSdk 21.
	BUILD_FFMPEG_SCRIPT="${FFMPEG_EXT_PATH}/jni/build_ffmpeg.sh"
	sed -i "s/armv7a-linux-androideabi16-/armv7a-linux-androideabi${AndroidApiLevel}-/g" "$BUILD_FFMPEG_SCRIPT"
	sed -i "s/i686-linux-android16-/i686-linux-android${AndroidApiLevel}-/g" "$BUILD_FFMPEG_SCRIPT"

	cd "${FFMPEG_EXT_PATH}/jni"
	./build_ffmpeg.sh "${FFMPEG_EXT_PATH}" "${NDK_PATH}" "${HOST_PLATFORM}" "${ENABLED_DECODERS[@]}"

	cd 	$BUILD_PATH

fi

#----------------------------------------------------- Build ExoPlayer ----------------------------------------------------#

if [ $1 = "buildexoplayer" ] || [ $1 = "all" ]; then

	cd 	$BUILD_PATH
	
	echo "Building Exoplayer..."
	if [ -f "$FFMPEG_CMAKE_FILE" ]; then
		if ! grep -q "max-page-size=16384" "$FFMPEG_CMAKE_FILE"; then
			sed -i '/project(libffmpegJNI C CXX)/a add_link_options("-Wl,-z,max-page-size=16384")' "$FFMPEG_CMAKE_FILE"
		fi
	fi
	cd "$EXOPLAYER_ROOT"
	SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
	if [ -n "$SDK_ROOT" ]; then
		echo "sdk.dir=$SDK_ROOT" > local.properties
		echo "ndk.dir=$NDK_PATH" >> local.properties
		if command -v cmake >/dev/null 2>&1; then
			echo "cmake.dir=/usr" >> local.properties
		fi
	fi
	./gradlew :extension-ffmpeg:assembleRelease --no-daemon

	cd 	$BUILD_PATH	

fi

if [ $1 = "deploy" ] || [ $1 = "all" ]; then

	cd 	$BUILD_PATH

	echo "Package Exoplayer..."

	cp "${FFMPEG_EXT_OUTPUT_PATH}/extension-ffmpeg-release.aar" "${ROOT_PATH}/../libs/extension-ffmpeg-${FFmpegExtVersion}.aar"



fi

#--------------------------------------------------------------------------------------------------------------------------#


