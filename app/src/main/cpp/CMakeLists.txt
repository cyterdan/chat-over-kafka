cmake_minimum_required(VERSION 3.10)

project(native_librdkafka)

# Set paths
set(LIBRDKAFKA_INCLUDE_DIR "${CMAKE_SOURCE_DIR}/includes/librdkafka")
set(OPENSSL_INCLUDE_DIR "${CMAKE_SOURCE_DIR}/includes/openssl")

# Add your JNI source
add_library(native-lib SHARED nativelib.cpp)

# Include directories
target_include_directories(native-lib PRIVATE
        ${LIBRDKAFKA_INCLUDE_DIR}
        ${OPENSSL_INCLUDE_DIR}
)

# Add prebuilt static libraries
add_library(rdkafka STATIC IMPORTED)
set_target_properties(rdkafka PROPERTIES
        IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/libs/${ANDROID_ABI}/librdkafka.so"
)

add_library(ssl STATIC IMPORTED)
set_target_properties(ssl PROPERTIES
        IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/libs/${ANDROID_ABI}/libssl.a"
)

add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES
        IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/libs/${ANDROID_ABI}/libcrypto.a"
)

find_library(log-lib log)

# Link everything
target_link_libraries(native-lib
        rdkafka
        ssl
        crypto
        android
        log
        z
)
