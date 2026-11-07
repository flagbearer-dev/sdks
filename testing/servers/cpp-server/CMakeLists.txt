# The C++ SDK behind the same GET /flags and GET /flush routes as node-server.ts.
cmake_minimum_required(VERSION 3.16)
project(flagbearer_cpp_fixture LANGUAGES CXX)

set(FLAGBEARER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(../../../cpp flagbearer)

add_executable(cpp-server main.cpp)
target_compile_features(cpp-server PRIVATE cxx_std_17)
target_link_libraries(cpp-server PRIVATE flagbearer::flagbearer)
