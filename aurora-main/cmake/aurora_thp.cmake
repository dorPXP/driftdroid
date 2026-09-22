# Ported from upstream aurora commit 3251f4e2 ("Add aurora::thp implementation"): a native THP
# video/audio decoder, verified bit-exact against Dolphin upstream. Used here to replace the
# guest's own THPVideoDecode/THPAudioDecode, which profiling showed dominate movie playback
# (the Select Cup screen runs at 9 FPS, and the attract demo is ~72% THP decode).
add_library(aurora_thp STATIC
  lib/dolphin/thp/THPAudio.cpp
  lib/dolphin/thp/THPDec.cpp
)
add_library(aurora::thp ALIAS aurora_thp)
set_target_properties(aurora_thp PROPERTIES FOLDER "aurora")

target_include_directories(aurora_thp PUBLIC include)
target_link_libraries(aurora_thp PUBLIC aurora::core)
