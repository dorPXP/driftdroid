# Aurora (renderer) fixes worth taking from patchzyy/aurora-wiicompiled — 2026-11-03

Source: `patchzyy/aurora-wiicompiled` @ 33067e3 (2026-10-29), the graphics layer WiiCompiled 0.9 and KartPad's beta engine use.
Ours: `aurora-main/` (an older Aurora with our Switch/deko3d, OpenGL ES, Adreno and EFB-RAM-copy work).

## Numbers
- 173 non-merge commits since 2026-06-13 touch `lib/` or `include/`.
- Checked each patch against our tree with `git apply --check --directory=aurora-main`:
  **20 apply cleanly** (all unrelated to rendering: card, time, GD helpers, headers), **4 are already in ours**
  (frsqrte UB, 1024 tex dimensions, SET_REG_FIELD, null texcoord stages), **149 do not apply**:
  28 because the file does not exist in ours (new subsystems: RmlUi, texture replacement, DDS/PNG io, NAND, wpad),
  121 because the context differs (our layout has no `gx/regs.cpp`, and the upstream commits depend on each other).
- So there are no free cherry-picks. Every item below is a hand-merge of a small change.

## Tier A — small, likely worth doing (correctness / fewer crashes)
| commit | what | why it may matter to us |
|---|---|---|
| 4aa01413 + 06c5ab8e | mask GX matrix indices to 6 bits; decode chan-ctrl attenuation and CP matrix indices | the #3 PowerVR "missing models" candidate; changes shader text, so the shader caches must be regenerated |
| 368c6044 | tolerate invalid TEV and sampler state | our `gx/shader.cpp` still has 16 FATAL checks; Retro Rewind tracks/odd GPUs can hit them |
| b684c0d2 | allow unbound textures (sample white) | turns a missing texture binding into a white texel instead of a crash |
| ca307d7f | indexed normal matrices + identity texture-matrix loads (#265) | correctness for models using indexed normals (we only have 2 references) |
| 116be71b | depth copy: uniform visibility and the z16 middle byte | depth-copy correctness |
| d090329f | treat fog fsel 1 and 3 as linear | tiny |

## Tier B — speed of shader/pipeline handling
- e1c25d51 canonicalize unused ksel / emboss source in pipeline configs (fewer shader variants, smaller caches)
- 5baadfcf dirty the pipeline only on a texture-matrix change
- f76acaac / 360bc0f2 async pipeline worker fixes (only relevant where we use a pipeline thread; our OpenGL ES backend is single-threaded)

## Tier C — big structural changes, a migration and not a cherry-pick
- 1617b33d fixed-point indirect texturing + texgen fixes (232 lines in the shader/state code)
- the render worker: cc65b53 FIFO processing thread, 78850a8d split frame for the GX worker thread, 6acd7f00 frame interpolation on the worker,
  plus the frame-packet / recording rework. This is the one change that could add real speed (GX handling off the game thread),
  and it touches everything our Switch and GLES code sits on.

## Already taken from this list (see docs/ported-changes.md)
e938637c, 0857ad86, 9381cc46 (GXLoadPosMtxIndx), 2bf0825, 26371ce, a906fc21, afa388d3 and parts of 6f14bde2; same-content already present:
diffuse fn, Z8 copies, INVAL_VTX, packed TEV alpha, partial clears, EFB alpha. 8e0ff967 (scissor offset) was reverted on purpose.

## Not for us
RmlUi / texture replacement / DDS+PNG io / NAND / card / wpad / DVD worker / Mac / CI.

## Caveat
"Likely worth doing" is from commit messages, sizes and the files they touch, plus quick greps of our tree. I have not run any of these
on a device. Each one needs a hand-merge, a PC build, and a check on the phone (and on the Switch where it touches shared code).

## Result of the first batch (2026-11-03)
Checked each Tier A item against our code. Only two were actually missing and are now ported:
- 4aa01413: matrix indices masked to 6 bits (3 places: `gx/command_processor.cpp` usage scan, `gx/shader.cpp` PNMTXIDX and TEXnMTXIDX loads).
- 368c6044 (the part that was missing): any invalid kcSel / kaSel reads as zero (we only handled 0x08-0x0B / 0x08-0x0F).
Already in ours: 06c5ab8e (CP matrix index decode, channel-control decode), the rest of 368c6044 (filter table, mip clamp, signed scissor clamp, alpha compare default), d090329f (fog types 1/3/9/11).
Not applicable: ca307d7f (the game does not use GXLoadNrmMtxIndx3x3 / GXLoadTexMtxIndx; unmatched identity XF writes are ignored here).
Not mapped, needs analysis: b684c0d2 (unbound texture sample-white: our shader builder tracks texture dependencies differently).
Left alone on purpose: 116be71b (Z16 depth copy writes the middle byte into alpha; our copy path is our own and this changes what a depth copy returns, so it needs a depth-copy test first).
The shader text changed, so the shader caches (APK `initial_pipeline_cache.db`, Switch `Cache/`) must be regenerated before a release that includes this.
