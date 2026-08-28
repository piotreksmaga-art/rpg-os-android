if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    get_filename_component(RPGOS_CPP_ROOT "${CMAKE_CURRENT_LIST_DIR}/.." ABSOLUTE)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES "${RPGOS_CPP_ROOT}/third_party/SPIRV-Headers/include"
    )
endif()
