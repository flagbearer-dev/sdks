cmake_minimum_required(VERSION 3.16)
project(flagbearer VERSION 1.0.0 LANGUAGES CXX)

if(CMAKE_SOURCE_DIR STREQUAL PROJECT_SOURCE_DIR)
  set(FLAGBEARER_TOP_LEVEL ON)
else()
  set(FLAGBEARER_TOP_LEVEL OFF)
endif()
option(FLAGBEARER_BUILD_TESTS "Build the Flagbearer SDK's tests" ${FLAGBEARER_TOP_LEVEL})
option(FLAGBEARER_WARNINGS_AS_ERRORS "Treat compiler warnings as errors" OFF)
set(FLAGBEARER_SANITIZE "" CACHE STRING "Sanitizers for the SDK and its tests: address,undefined or thread")

find_package(CURL 7.68 REQUIRED)
find_package(Threads REQUIRED)

add_library(flagbearer
  src/client.cpp
  src/evaluate.cpp
  src/exposures.cpp
  src/http.cpp
  src/json.cpp
  src/refresh.cpp
  src/stream.cpp)
add_library(flagbearer::flagbearer ALIAS flagbearer)
target_compile_features(flagbearer PUBLIC cxx_std_17)
set_target_properties(flagbearer PROPERTIES CXX_EXTENSIONS OFF POSITION_INDEPENDENT_CODE ON)
target_include_directories(flagbearer
  PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
    $<INSTALL_INTERFACE:include>
  PRIVATE src)
target_link_libraries(flagbearer PRIVATE CURL::libcurl Threads::Threads)

function(flagbearer_strict target)
  if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
    target_compile_options(${target} PRIVATE -Wall -Wextra -Wpedantic)
    if(FLAGBEARER_WARNINGS_AS_ERRORS)
      target_compile_options(${target} PRIVATE -Werror)
    endif()
    if(FLAGBEARER_SANITIZE)
      target_compile_options(${target} PRIVATE -fsanitize=${FLAGBEARER_SANITIZE} -fno-omit-frame-pointer)
      target_link_options(${target} PRIVATE -fsanitize=${FLAGBEARER_SANITIZE})
    endif()
  endif()
endfunction()
flagbearer_strict(flagbearer)

include(GNUInstallDirs)
include(CMakePackageConfigHelpers)
install(TARGETS flagbearer EXPORT flagbearerTargets
  ARCHIVE DESTINATION ${CMAKE_INSTALL_LIBDIR}
  LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
  RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR})
install(DIRECTORY include/ DESTINATION ${CMAKE_INSTALL_INCLUDEDIR})
install(EXPORT flagbearerTargets NAMESPACE flagbearer::
  DESTINATION ${CMAKE_INSTALL_LIBDIR}/cmake/flagbearer)
configure_package_config_file(cmake/flagbearerConfig.cmake.in
  ${CMAKE_CURRENT_BINARY_DIR}/flagbearerConfig.cmake
  INSTALL_DESTINATION ${CMAKE_INSTALL_LIBDIR}/cmake/flagbearer)
write_basic_package_version_file(${CMAKE_CURRENT_BINARY_DIR}/flagbearerConfigVersion.cmake
  COMPATIBILITY SameMinorVersion)
install(FILES
  ${CMAKE_CURRENT_BINARY_DIR}/flagbearerConfig.cmake
  ${CMAKE_CURRENT_BINARY_DIR}/flagbearerConfigVersion.cmake
  DESTINATION ${CMAKE_INSTALL_LIBDIR}/cmake/flagbearer)

if(FLAGBEARER_BUILD_TESTS)
  enable_testing()
  # doctest's own CMake file predates CMake 4, so only its single header is fetched.
  set(FLAGBEARER_DOCTEST "${CMAKE_CURRENT_BINARY_DIR}/_deps/doctest/doctest/doctest.h")
  if(NOT EXISTS "${FLAGBEARER_DOCTEST}")
    file(DOWNLOAD
      https://raw.githubusercontent.com/doctest/doctest/v2.4.11/doctest/doctest.h
      "${FLAGBEARER_DOCTEST}"
      EXPECTED_HASH SHA256=44faa038e9c3f9728efbda143748d01124ea0a27f4bf78f35a15d8fab2e039fb
      TLS_VERIFY ON)
  endif()

  add_executable(flagbearer_tests
    tests/main.cpp
    tests/fake_sdk.cpp
    tests/fixtures_test.cpp
    tests/stream_test.cpp
    tests/client_test.cpp)
  target_include_directories(flagbearer_tests PRIVATE src)
  target_include_directories(flagbearer_tests SYSTEM PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/_deps/doctest")
  target_link_libraries(flagbearer_tests PRIVATE flagbearer CURL::libcurl Threads::Threads)
  target_compile_definitions(flagbearer_tests PRIVATE
    FLAGBEARER_FIXTURES="${CMAKE_CURRENT_SOURCE_DIR}/../fixtures/evaluation.json")
  flagbearer_strict(flagbearer_tests)
  add_test(NAME flagbearer_tests COMMAND flagbearer_tests)
endif()
