cmake_minimum_required(VERSION 3.10.2)
project(sagetransport C)

add_library(sagetransport SHARED
    sage_transport.c
    sage_transport_jni.c
)

target_include_directories(sagetransport PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(sagetransport
    android
    log
)

if (CMAKE_VERSION VERSION_GREATER_EQUAL "3.13")
    target_link_options(sagetransport PRIVATE -Wl,-z,max-page-size=16384)
else()
    set_target_properties(sagetransport PROPERTIES LINK_FLAGS "-Wl,-z,max-page-size=16384")
endif()

target_compile_options(sagetransport PRIVATE -std=c11 -O2 -Wall -Wextra -Wno-unused-parameter)
